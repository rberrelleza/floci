package io.github.hectorvent.floci.services.rds.container;

import io.github.hectorvent.floci.services.rds.model.DatabaseEngine;

import java.util.ArrayList;
import java.util.List;

/**
 * Engine-specific runtime settings shared by RDS execution backends.
 */
public final class RdsEngineRuntime {

    private RdsEngineRuntime() {
    }

    public static String dataPath(DatabaseEngine engine, String image) {
        return switch (engine) {
            case POSTGRES -> postgresDataPath(image);
            case MYSQL, MARIADB -> "/var/lib/mysql";
        };
    }

    public static List<String> environment(DatabaseEngine engine, String masterUsername,
                                            String masterPassword, String dbName) {
        String effectiveUser = masterUsername != null && !masterUsername.isBlank()
                ? masterUsername : "postgres";
        String effectiveDb = dbName != null && !dbName.isBlank() ? dbName : effectiveUser;
        List<String> envs = new ArrayList<>();
        switch (engine) {
            case POSTGRES -> {
                envs.add("POSTGRES_USER=" + effectiveUser);
                envs.add("POSTGRES_PASSWORD=" + masterPassword);
                envs.add("POSTGRES_DB=" + effectiveDb);
                envs.add("POSTGRES_HOST_AUTH_METHOD=md5");
            }
            case MYSQL -> {
                envs.add("MYSQL_ROOT_PASSWORD=" + masterPassword);
                if (!"root".equals(effectiveUser)) {
                    envs.add("MYSQL_USER=" + effectiveUser);
                    envs.add("MYSQL_PASSWORD=" + masterPassword);
                }
                envs.add("MYSQL_DATABASE=" + effectiveDb);
            }
            case MARIADB -> {
                envs.add("MARIADB_ROOT_PASSWORD=" + masterPassword);
                if (!"root".equals(effectiveUser)) {
                    envs.add("MARIADB_USER=" + effectiveUser);
                    envs.add("MARIADB_PASSWORD=" + masterPassword);
                }
                envs.add("MARIADB_DATABASE=" + effectiveDb);
            }
        }
        return envs;
    }

    public static List<String> command(DatabaseEngine engine) {
        return engine == DatabaseEngine.MYSQL
                ? List.of("--default-authentication-plugin=mysql_native_password")
                : List.of();
    }

    public static long fsGroup(DatabaseEngine engine) {
        return 999L;
    }

    public static String postgresIamRoleInitSql() {
        return """
                DO $$
                BEGIN
                    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rds_iam') THEN
                        CREATE ROLE rds_iam;
                    END IF;
                END
                $$;
                """;
    }

    private static String postgresDataPath(String image) {
        return postgresImageMajorVersion(image) >= 18
                ? "/var/lib/postgresql" : "/var/lib/postgresql/data";
    }

    private static int postgresImageMajorVersion(String image) {
        if (image == null || image.isBlank()) {
            return -1;
        }
        String reference = image;
        int digestSeparator = reference.indexOf('@');
        if (digestSeparator >= 0) {
            reference = reference.substring(0, digestSeparator);
        }
        int slashSeparator = reference.lastIndexOf('/');
        int tagSeparator = reference.lastIndexOf(':');
        if (tagSeparator < slashSeparator || tagSeparator == reference.length() - 1) {
            return -1;
        }
        String tag = reference.substring(tagSeparator + 1);
        int end = 0;
        while (end < tag.length() && Character.isDigit(tag.charAt(end))) {
            end++;
        }
        return end == 0 ? -1 : Integer.parseInt(tag.substring(0, end));
    }
}
