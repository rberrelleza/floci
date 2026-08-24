package io.github.hectorvent.floci.services.rds.container;

import io.github.hectorvent.floci.services.rds.model.DatabaseEngine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RdsEngineRuntimeTest {

    @Test
    void preservesEngineDataPathsAndPostgresVersionBehavior() {
        assertEquals("/var/lib/postgresql/data",
                RdsEngineRuntime.dataPath(DatabaseEngine.POSTGRES, "postgres:17"));
        assertEquals("/var/lib/postgresql",
                RdsEngineRuntime.dataPath(DatabaseEngine.POSTGRES, "postgres:18.4"));
        assertEquals("/var/lib/mysql", RdsEngineRuntime.dataPath(DatabaseEngine.MYSQL, "mysql:8"));
        assertEquals("/var/lib/mysql", RdsEngineRuntime.dataPath(DatabaseEngine.MARIADB, "mariadb:11"));
    }

    @Test
    void preservesEnvironmentAndCommandPerEngine() {
        assertEquals(List.of(
                "MYSQL_ROOT_PASSWORD=password",
                "MYSQL_USER=app",
                "MYSQL_PASSWORD=password",
                "MYSQL_DATABASE=db"),
                RdsEngineRuntime.environment(DatabaseEngine.MYSQL, "app", "password", "db"));
        assertEquals(List.of("--default-authentication-plugin=mysql_native_password"),
                RdsEngineRuntime.command(DatabaseEngine.MYSQL));
        assertEquals(List.of(), RdsEngineRuntime.command(DatabaseEngine.POSTGRES));
    }
}
