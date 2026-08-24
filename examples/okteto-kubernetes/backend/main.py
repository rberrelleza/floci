import json
import os
import time
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from typing import Any
from urllib.parse import urlparse

import boto3
import psycopg
import redis
from botocore.exceptions import ClientError
from botocore.config import Config
from fastapi import FastAPI, HTTPException, Query
from opensearchpy import OpenSearch
from pydantic import BaseModel


FLOCI_ENDPOINT = os.getenv("FLOCI_ENDPOINT", "http://floci:4566")
REGION = os.getenv("AWS_DEFAULT_REGION", "us-east-1")
AWS_KWARGS = {
    "endpoint_url": FLOCI_ENDPOINT,
    "region_name": REGION,
    "aws_access_key_id": os.getenv("AWS_ACCESS_KEY_ID", "demo"),
    "aws_secret_access_key": os.getenv("AWS_SECRET_ACCESS_KEY", "demo"),
}
AWS_CLIENT_CONFIG = Config(
    connect_timeout=10,
    read_timeout=900,
    retries={"max_attempts": 0},
)
RDS_ID = "demo-postgres"
CACHE_ID = "demo-valkey"
DOMAIN_ID = "demo-search"
INDEX_NAME = "demo-items"

state: dict[str, Any] = {
    "rds": None,
    "cache": None,
    "search": None,
    "postgres": None,
    "valkey": None,
    "opensearch": None,
}


class Item(BaseModel):
    value: str


def missing(error: ClientError, *codes: str) -> bool:
    return error.response.get("Error", {}).get("Code") in codes


def rds_client():
    return boto3.client("rds", config=AWS_CLIENT_CONFIG, **AWS_KWARGS)


def cache_client():
    return boto3.client("elasticache", config=AWS_CLIENT_CONFIG, **AWS_KWARGS)


def search_client():
    return boto3.client("opensearch", config=AWS_CLIENT_CONFIG, **AWS_KWARGS)


def provision_rds() -> dict[str, Any]:
    client = rds_client()
    try:
        response = client.describe_db_instances(DBInstanceIdentifier=RDS_ID)
    except ClientError as error:
        if not missing(error, "DBInstanceNotFound", "DBInstanceNotFoundFault"):
            raise
        client.create_db_instance(
            DBInstanceIdentifier=RDS_ID,
            DBInstanceClass="db.t3.micro",
            Engine="postgres",
            EngineVersion="16",
            MasterUsername="demo",
            MasterUserPassword="demo-password",
            DBName="demo",
            AllocatedStorage=5,
        )
        response = client.describe_db_instances(DBInstanceIdentifier=RDS_ID)
    return wait_for(
        lambda: rds_client().describe_db_instances(DBInstanceIdentifier=RDS_ID)["DBInstances"][0],
        lambda item: item.get("DBInstanceStatus") == "available" and item.get("Endpoint"),
        "RDS",
    )


def provision_cache() -> dict[str, Any]:
    client = cache_client()
    try:
        groups = client.describe_replication_groups(ReplicationGroupId=CACHE_ID).get(
            "ReplicationGroups", []
        )
    except ClientError as error:
        if not missing(error, "ReplicationGroupNotFoundFault", "ReplicationGroupNotFound"):
            raise
        groups = []
    if not groups:
        client.create_replication_group(
            ReplicationGroupId=CACHE_ID,
            ReplicationGroupDescription="Floci Kubernetes demo cache",
            Engine="valkey",
        )
    return wait_for(
        lambda: cache_client().describe_replication_groups(
            ReplicationGroupId=CACHE_ID
        )["ReplicationGroups"][0],
        lambda item: item.get("Status") == "available"
        and item.get("ConfigurationEndpoint"),
        "ElastiCache",
    )


def provision_search() -> dict[str, Any]:
    client = search_client()
    try:
        response = client.describe_domain(DomainName=DOMAIN_ID)
        domain = response["DomainStatus"]
    except ClientError as error:
        if not missing(error, "ResourceNotFoundException", "ValidationException"):
            raise
        client.create_domain(
            DomainName=DOMAIN_ID,
            EngineVersion="OpenSearch_2.11",
            ClusterConfig={"InstanceType": "t3.small.search", "InstanceCount": 1},
            EBSOptions={"EBSEnabled": True, "VolumeType": "gp3", "VolumeSize": 5},
        )
    return wait_for(
        lambda: search_client().describe_domain(DomainName=DOMAIN_ID)["DomainStatus"],
        lambda item: item.get("Processing") is False and item.get("Endpoint"),
        "OpenSearch",
    )


def wait_for(loader, ready, name: str) -> dict[str, Any]:
    deadline = time.monotonic() + 600
    last: dict[str, Any] = {}
    while time.monotonic() < deadline:
        last = loader()
        if ready(last):
            return last
        time.sleep(2)
    raise RuntimeError(f"{name} did not become available: {last}")


def connect_services() -> None:
    rds_endpoint = state["rds"]["Endpoint"]
    state["postgres"] = psycopg.connect(
        host=rds_endpoint["Address"],
        port=rds_endpoint["Port"],
        dbname="demo",
        user="demo",
        password="demo-password",
        connect_timeout=10,
    )
    with state["postgres"].cursor() as cursor:
        cursor.execute(
            "CREATE TABLE IF NOT EXISTS items "
            "(id SERIAL PRIMARY KEY, value TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW())"
        )
    state["postgres"].commit()

    cache_endpoint = state["cache"]["ConfigurationEndpoint"]
    state["valkey"] = redis.Redis(
        host=cache_endpoint["Address"],
        port=cache_endpoint["Port"],
        decode_responses=True,
        socket_connect_timeout=10,
    )
    search_endpoint = state["search"]["Endpoint"]
    parsed_endpoint = urlparse(
        search_endpoint if "://" in search_endpoint else f"//{search_endpoint}"
    )
    state["opensearch"] = OpenSearch(
        hosts=[{
            "host": parsed_endpoint.hostname,
            "port": parsed_endpoint.port or 9200,
        }],
        use_ssl=parsed_endpoint.scheme == "https",
        verify_certs=False,
        ssl_show_warn=False,
        timeout=10,
    )
    if not state["opensearch"].indices.exists(index=INDEX_NAME):
        state["opensearch"].indices.create(index=INDEX_NAME)


def endpoint_summary() -> dict[str, Any]:
    rds_endpoint = state["rds"]["Endpoint"]
    cache_endpoint = state["cache"]["ConfigurationEndpoint"]
    return {
        "rds": {
            "status": "available",
            "host": rds_endpoint["Address"],
            "port": rds_endpoint["Port"],
        },
        "elasticache": {
            "status": "available",
            "host": cache_endpoint["Address"],
            "port": cache_endpoint["Port"],
        },
        "opensearch": {
            "status": "available",
            "host": state["search"]["Endpoint"],
            "port": 9200,
        },
    }


@asynccontextmanager
async def lifespan(_: FastAPI):
    state["rds"] = provision_rds()
    state["cache"] = provision_cache()
    state["search"] = provision_search()
    connect_services()
    yield
    if state["postgres"]:
        state["postgres"].close()
    if state["valkey"]:
        state["valkey"].close()


app = FastAPI(title="Floci Kubernetes Demo", lifespan=lifespan)


@app.get("/api/health")
def health():
    try:
        return {"status": "ok", "services": endpoint_summary()}
    except Exception as error:
        return {"status": "starting", "error": str(error)}


@app.post("/api/items")
def add_item(item: Item):
    with state["postgres"].cursor() as cursor:
        cursor.execute(
            "INSERT INTO items (value) VALUES (%s) RETURNING id, value, created_at",
            (item.value,),
        )
        row = cursor.fetchone()
    state["postgres"].commit()
    result = {"id": row[0], "value": row[1], "created_at": row[2].isoformat()}
    state["valkey"].delete("items")
    state["valkey"].set(f"item:{result['id']}", json.dumps(result))
    state["opensearch"].index(index=INDEX_NAME, id=str(result["id"]), body=result, refresh=True)
    return {"item": result, "stored_in": ["postgres", "valkey", "opensearch"]}


@app.get("/api/items")
def list_items():
    cached = state["valkey"].get("items")
    if cached:
        return {"items": json.loads(cached), "cache_hit": True}
    with state["postgres"].cursor() as cursor:
        cursor.execute("SELECT id, value, created_at FROM items ORDER BY id")
        items = [
            {"id": row[0], "value": row[1], "created_at": row[2].astimezone(timezone.utc).isoformat()}
            for row in cursor.fetchall()
        ]
    state["valkey"].set("items", json.dumps(items))
    return {"items": items, "cache_hit": False}


@app.get("/api/search")
def search(q: str = Query(min_length=1)):
    response = state["opensearch"].search(
        index=INDEX_NAME,
        body={"query": {"match": {"value": q}}},
    )
    return {
        "query": q,
        "hits": [
            {"id": hit["_id"], **hit["_source"]}
            for hit in response["hits"]["hits"]
        ],
    }
