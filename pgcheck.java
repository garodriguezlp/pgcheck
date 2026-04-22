///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17+
//DEPS info.picocli:picocli:4.7.6
//DEPS org.postgresql:postgresql:42.7.3

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.PropertiesDefaultProvider;

import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.Callable;

@Command(
    name = "pgcheck",
    mixinStandardHelpOptions = true,
    version = "pgcheck 1.0",
    description = "One-shot PostgreSQL executor for LLM agents",
    defaultValueProvider = pgcheck.DefaultProvider.class
)
public class pgcheck implements Callable<Integer> {

    @Option(names = "--url",
            description = "JDBC connection URL (operator-managed)",
            descriptionKey = "url")
    String url = "jdbc:postgresql://localhost:5432/postgres";

    @Option(names = "--username",
            description = "Database username (operator-managed)",
            descriptionKey = "username")
    String username = "postgres";

    @Option(names = "--password",
            description = "Database password (operator-managed)",
            descriptionKey = "password")
    String password = "";

    @Option(names = "--allow-writes",
            description = "Allow DML write operations; DDL always blocked (operator-managed)",
            descriptionKey = "allow-writes")
    boolean allowWrites = false;

    @Option(names = "--sql",
            description = "SQL statement to execute (inline)")
    String sql;

    @Option(names = "--file",
            description = "Path to a .sql file to execute")
    File file;

    @Option(names = "--stdin",
            description = "Read SQL from standard input")
    boolean stdin;

    @Option(names = "--max-rows",
            description = "Maximum rows returned for SELECT queries (default: 100)",
            descriptionKey = "max-rows")
    int maxRows = 100;

    @Option(names = "--timeout",
            description = "Query timeout in seconds (default: 30)",
            descriptionKey = "timeout")
    int timeout = 30;

    public static void main(String... args) {
        System.exit(new CommandLine(new pgcheck()).execute(args));
    }

    @Override
    public Integer call() {
        int inputCount = (sql != null ? 1 : 0) + (file != null ? 1 : 0) + (stdin ? 1 : 0);
        if (inputCount == 0) {
            System.err.println("Error: provide exactly one of --sql, --file, or --stdin");
            System.out.println(Json.obj()
                .put("status", "error")
                .put("error_type", "input_error")
                .put("message", "Provide exactly one of --sql, --file, or --stdin")
                .build());
            return 3;
        }
        if (inputCount > 1) {
            System.err.println("Error: only one of --sql, --file, or --stdin may be provided");
            System.out.println(Json.obj()
                .put("status", "error")
                .put("error_type", "input_error")
                .put("message", "Only one of --sql, --file, or --stdin may be provided")
                .build());
            return 3;
        }

        String sqlText;
        try {
            if (sql != null) {
                sqlText = sql;
            } else if (file != null) {
                sqlText = Files.readString(file.toPath());
            } else {
                sqlText = new String(System.in.readAllBytes());
            }
        } catch (IOException e) {
            System.err.println("Error reading SQL input: " + e.getMessage());
            System.out.println(Json.obj()
                .put("status", "error")
                .put("error_type", "input_error")
                .put("message", "Failed to read SQL: " + e.getMessage())
                .build());
            return 3;
        }

        sqlText = sqlText.strip();
        if (sqlText.isEmpty()) {
            System.err.println("Error: SQL input is empty");
            System.out.println(Json.obj()
                .put("status", "error")
                .put("error_type", "input_error")
                .put("message", "SQL input is empty")
                .build());
            return 3;
        }

        String statementType = detectStatementType(sqlText);

        if (isDdl(statementType)) {
            System.out.println(Json.obj()
                .put("status", "error")
                .put("error_type", "policy_violation")
                .put("message", "DDL statements (CREATE, DROP, ALTER, TRUNCATE) are not allowed.")
                .put("statement_type", statementType)
                .build());
            return 2;
        }

        if (!allowWrites && !isRead(statementType)) {
            System.out.println(Json.obj()
                .put("status", "error")
                .put("error_type", "policy_violation")
                .put("message", "Write operations are disabled. Set allow-writes=true in ~/.pgcheck.properties to enable.")
                .put("statement_type", statementType)
                .build());
            return 2;
        }

        long start = System.currentTimeMillis();
        try (Connection conn = DriverManager.getConnection(url, username, password)) {
            conn.setAutoCommit(true);
            try (Statement stmt = conn.createStatement()) {
                stmt.setQueryTimeout(timeout);
                if (isRead(statementType)) {
                    stmt.setMaxRows(maxRows + 1);
                    boolean hasRs = stmt.execute(sqlText);
                    long duration = System.currentTimeMillis() - start;
                    if (hasRs) {
                        try (ResultSet rs = stmt.getResultSet()) {
                            return emitSelectResult(rs, statementType, duration);
                        }
                    }
                    // Non-result SELECT (e.g. EXPLAIN without ANALYZE in some modes)
                    System.out.println(Json.obj()
                        .put("status", "ok")
                        .put("statement_type", statementType)
                        .putRaw("row_count", 0L)
                        .put("truncated", false)
                        .putRaw("columns", "[]")
                        .putRaw("rows", "[]")
                        .putRaw("duration_ms", duration)
                        .build());
                    return 0;
                } else {
                    int affected = stmt.executeUpdate(sqlText);
                    long duration = System.currentTimeMillis() - start;
                    System.out.println(Json.obj()
                        .put("status", "ok")
                        .put("statement_type", statementType)
                        .putRaw("rows_affected", affected)
                        .putRaw("duration_ms", duration)
                        .build());
                    return 0;
                }
            }
        } catch (SQLTimeoutException e) {
            long duration = System.currentTimeMillis() - start;
            System.err.println("Query timed out: " + e.getMessage());
            System.out.println(Json.obj()
                .put("status", "error")
                .put("error_type", "sql_error")
                .put("message", "Query timed out after " + timeout + " seconds")
                .put("sql_state", e.getSQLState() != null ? e.getSQLState() : "")
                .putRaw("duration_ms", duration)
                .build());
            return 1;
        } catch (SQLException e) {
            long duration = System.currentTimeMillis() - start;
            if (isConnectionError(e)) {
                System.err.println("Connection error: " + e.getMessage());
                System.out.println(Json.obj()
                    .put("status", "error")
                    .put("error_type", "connection_error")
                    .put("message", e.getMessage())
                    .build());
                return 4;
            }
            System.err.println("SQL error: " + e.getMessage());
            System.out.println(Json.obj()
                .put("status", "error")
                .put("error_type", "sql_error")
                .put("message", e.getMessage())
                .put("sql_state", e.getSQLState() != null ? e.getSQLState() : "")
                .putRaw("duration_ms", duration)
                .build());
            return 1;
        }
    }

    private int emitSelectResult(ResultSet rs, String statementType, long duration) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int colCount = meta.getColumnCount();

        List<String> names = new ArrayList<>(colCount);
        List<String> types = new ArrayList<>(colCount);
        for (int i = 1; i <= colCount; i++) {
            names.add(meta.getColumnName(i));
            types.add(meta.getColumnTypeName(i));
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        boolean truncated = false;
        while (rs.next()) {
            if (rows.size() >= maxRows) {
                truncated = true;
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= colCount; i++) {
                row.put(names.get(i - 1), rs.getObject(i));
            }
            rows.add(row);
        }

        StringBuilder cols = new StringBuilder("[");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) cols.append(",");
            cols.append("{\"name\":").append(Json.str(names.get(i)))
                .append(",\"type\":").append(Json.str(types.get(i))).append("}");
        }
        cols.append("]");

        StringBuilder rowsJson = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) rowsJson.append(",");
            rowsJson.append("{");
            int j = 0;
            for (Map.Entry<String, Object> e : rows.get(i).entrySet()) {
                if (j++ > 0) rowsJson.append(",");
                rowsJson.append(Json.str(e.getKey())).append(":").append(Json.val(e.getValue()));
            }
            rowsJson.append("}");
        }
        rowsJson.append("]");

        System.out.println("{" +
            "\"status\":\"ok\"," +
            "\"statement_type\":" + Json.str(statementType) + "," +
            "\"row_count\":" + rows.size() + "," +
            "\"truncated\":" + truncated + "," +
            "\"columns\":" + cols + "," +
            "\"rows\":" + rowsJson + "," +
            "\"duration_ms\":" + duration +
            "}");
        return 0;
    }

    private String detectStatementType(String sql) {
        // First non-whitespace token, case-insensitive (per spec: intentionally simple)
        String first = sql.strip().split("\\s+")[0].toUpperCase();
        return switch (first) {
            case "SELECT", "WITH", "TABLE", "VALUES", "SHOW", "EXPLAIN" -> "SELECT";
            case "INSERT" -> "INSERT";
            case "UPDATE" -> "UPDATE";
            case "DELETE" -> "DELETE";
            case "MERGE" -> "MERGE";
            case "CREATE", "DROP", "ALTER", "TRUNCATE", "RENAME", "COMMENT" -> first;
            default -> "UNKNOWN";
        };
    }

    private boolean isRead(String statementType) {
        return "SELECT".equals(statementType);
    }

    private boolean isDdl(String statementType) {
        return Set.of("CREATE", "DROP", "ALTER", "TRUNCATE", "RENAME", "COMMENT").contains(statementType);
    }

    private boolean isConnectionError(SQLException e) {
        String state = e.getSQLState();
        // SQL state class 08 = connection exception; null state usually means no connection was established
        if (state == null) return true;
        return state.startsWith("08");
    }

    // Minimal JSON builder — no external deps, handles all JDBC value types
    static class Json {
        static String str(String s) {
            if (s == null) return "null";
            var sb = new StringBuilder("\"");
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"'  -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    case '\b' -> sb.append("\\b");
                    case '\f' -> sb.append("\\f");
                    default -> {
                        if (c < 0x20) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                    }
                }
            }
            return sb.append("\"").toString();
        }

        static String val(Object v) {
            if (v == null) return "null";
            if (v instanceof Boolean b) return b.toString();
            if (v instanceof Integer || v instanceof Long ||
                v instanceof Short || v instanceof Byte) return v.toString();
            if (v instanceof BigDecimal || v instanceof Float || v instanceof Double) return v.toString();
            if (v instanceof byte[] bytes) {
                // Render binary as hex string
                var sb = new StringBuilder("\"\\\\x");
                for (byte b : bytes) sb.append(String.format("%02x", b));
                return sb.append("\"").toString();
            }
            return str(v.toString());
        }

        static Builder obj() { return new Builder(); }

        static class Builder {
            private final StringBuilder sb = new StringBuilder("{");
            private boolean first = true;

            Builder put(String key, String value) {
                sep();
                sb.append(str(key)).append(":").append(str(value));
                return this;
            }

            Builder put(String key, boolean value) {
                sep();
                sb.append(str(key)).append(":").append(value);
                return this;
            }

            // For pre-built JSON fragments (arrays, raw numbers)
            Builder putRaw(String key, long value) {
                sep();
                sb.append(str(key)).append(":").append(value);
                return this;
            }

            Builder putRaw(String key, String rawJson) {
                sep();
                sb.append(str(key)).append(":").append(rawJson);
                return this;
            }

            private void sep() {
                if (!first) sb.append(",");
                first = false;
            }

            String build() { return sb.append("}").toString(); }
        }
    }

    static class DefaultProvider extends PropertiesDefaultProvider {
        DefaultProvider() {
            super(new File(System.getProperty("user.home"), ".pgcheck.properties"));
        }
    }
}
