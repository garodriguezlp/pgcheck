///usr/bin/env jbang "$0" "$@" ; exit $?

//JAVA 17+

//COMPILE_OPTIONS -encoding UTF-8
//RUNTIME_OPTIONS -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8

//DEPS info.picocli:picocli:4.7.6
//DEPS org.postgresql:postgresql:42.7.3
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.3
//DEPS org.tinylog:tinylog-api:2.7.0
//DEPS org.tinylog:tinylog-impl:2.7.0

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.tinylog.Logger;
import org.tinylog.configuration.Configuration;
import picocli.CommandLine;
import picocli.CommandLine.*;

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

    static {
        configureLogging(false);
    }

    @Option(names = {"-v", "--verbose"}, description = "Enable verbose/debug logging to stderr")
    boolean verbose;

    @ArgGroup(exclusive = false, heading = "%nOperator-managed options:%n")
    DatabaseConfig dbConfig = new DatabaseConfig();

    @ArgGroup(exclusive = true, multiplicity = "1")
    SqlInputGroup sqlInput;

    @ArgGroup(exclusive = false, heading = "%nQuery options:%n")
    QueryOptions queryOptions = new QueryOptions();

    static void configureLogging(boolean verbose) {
        Map<String, String> config = new HashMap<>();
        config.put("writer", "console");
        config.put("writer.stream", "err");
        config.put("writer.format", "{date:HH:mm:ss.SSS} {level|min-size=5} {class-name} - {message}");
        config.put("writer.level", verbose ? "DEBUG" : "WARN");
        Configuration.replace(config);
    }

    public static void main(String... args) {
        System.exit(new CommandLine(new pgcheck()).execute(args));
    }

    @Override
    public Integer call() {
        if (verbose) configureLogging(true);
        ResponseWriter writer = new ResponseWriter();

        String sql;
        try {
            sql = sqlInput.resolve();
        } catch (InputException e) {
            writer.writeInputError(e.getMessage());
            return ExitCode.INPUT_ERROR;
        }

        StatementType type = StatementType.classify(sql);

        try {
            new PolicyGuard(dbConfig.allowWrites).enforce(type);
        } catch (PolicyViolationException e) {
            writer.writePolicyViolation(e);
            return ExitCode.POLICY_VIOLATION;
        }

        QueryExecutor executor = new QueryExecutor(
            dbConfig.url, dbConfig.username, dbConfig.password,
            queryOptions.timeout, queryOptions.maxRows
        );
        long start = System.currentTimeMillis();
        try {
            QueryResult result = executor.execute(sql, type);
            writer.writeSuccess(result);
            return ExitCode.OK;
        } catch (SQLException e) {
            long durationMs = System.currentTimeMillis() - start;
            if (QueryExecutor.isConnectionError(e)) {
                writer.writeConnectionError(e);
                return ExitCode.CONNECTION_ERROR;
            }
            writer.writeSqlError(e, durationMs);
            return ExitCode.SQL_ERROR;
        }
    }

    static class DefaultProvider extends PropertiesDefaultProvider {
        DefaultProvider() {
            super(new File(System.getProperty("user.home"), ".pgcheck.properties"));
        }
    }
}

// ---- ExitCode ----
class ExitCode {
    static final int OK = 0;
    static final int SQL_ERROR = 1;
    static final int POLICY_VIOLATION = 2;
    static final int INPUT_ERROR = 3;
    static final int CONNECTION_ERROR = 4;
}

// ---- DatabaseConfig ----
class DatabaseConfig {
    @Option(names = "--url",
            description = "JDBC connection URL (operator-managed)",
            descriptionKey = "url")
    String url = "jdbc:postgresql://localhost:5432/pgcheck_demo";

    @Option(names = "--username",
            description = "Database username (operator-managed)",
            descriptionKey = "username")
    String username = "pgcheck";

    @Option(names = "--password",
            description = "Database password (operator-managed)",
            descriptionKey = "password")
    String password = "pgcheck";

    @Option(names = "--allow-writes",
            description = "Allow DML write operations; DDL always blocked (operator-managed)",
            descriptionKey = "allow-writes")
    boolean allowWrites = false;
}

// ---- QueryOptions ----
class QueryOptions {
    @Option(names = "--max-rows",
            description = "Maximum rows returned for SELECT queries (default: 100)",
            descriptionKey = "max-rows",
            converter = QueryOptions.PositiveIntConverter.class)
    int maxRows = 100;

    @Option(names = "--timeout",
            description = "Query timeout in seconds (default: 30)",
            descriptionKey = "timeout",
            converter = QueryOptions.PositiveIntConverter.class)
    int timeout = 30;

    static class PositiveIntConverter implements ITypeConverter<Integer> {
        @Override
        public Integer convert(String value) throws Exception {
            try {
                int v = Integer.parseInt(value);
                if (v < 1) throw new TypeConversionException("Value must be >= 1, got: " + value);
                return v;
            } catch (NumberFormatException e) {
                throw new TypeConversionException("Expected an integer, got: " + value);
            }
        }
    }
}

// ---- SqlInputGroup ----
class SqlInputGroup {
    @Option(names = "--sql", description = "SQL statement to execute (inline)")
    String sql;

    @Option(names = "--file",
            description = "Path to a .sql file to execute",
            converter = SqlInputGroup.FileConverter.class)
    File file;

    @Option(names = "--stdin", description = "Read SQL from standard input")
    boolean stdin;

    String resolve() throws InputException {
        try {
            String text = sql != null ? readInlineSql() : file != null ? readFromFile() : readFromStdin();
            text = text.strip();
            if (text.isEmpty()) throw new InputException("SQL input is empty");
            return text;
        } catch (IOException e) {
            throw new InputException("Failed to read SQL: " + e.getMessage());
        }
    }

    private String readInlineSql() { return sql; }
    private String readFromFile() throws IOException { return Files.readString(file.toPath()); }
    private String readFromStdin() throws IOException { return new String(System.in.readAllBytes()); }

    static class FileConverter implements ITypeConverter<File> {
        @Override
        public File convert(String value) throws Exception {
            File f = new File(value);
            if (!f.exists()) throw new TypeConversionException("File not found: " + value);
            if (!f.canRead()) throw new TypeConversionException("File not readable: " + value);
            return f;
        }
    }
}

// ---- InputException ----
class InputException extends Exception {
    InputException(String message) { super(message); }
}

// ---- StatementType ----
enum StatementType {
    SELECT, INSERT, UPDATE, DELETE, MERGE, DDL, UNKNOWN;

    boolean isRead() { return this == SELECT; }
    boolean isDdl() { return this == DDL; }
    boolean isWriteDml() { return this == INSERT || this == UPDATE || this == DELETE || this == MERGE; }

    static StatementType classify(String sql) {
        String first = sql.strip().split("\\s+")[0].toUpperCase();
        return switch (first) {
            case "SELECT", "WITH", "TABLE", "VALUES", "SHOW", "EXPLAIN" -> SELECT;
            case "INSERT" -> INSERT;
            case "UPDATE" -> UPDATE;
            case "DELETE" -> DELETE;
            case "MERGE" -> MERGE;
            case "CREATE", "DROP", "ALTER", "TRUNCATE", "RENAME", "COMMENT" -> DDL;
            default -> UNKNOWN;
        };
    }
}

// ---- PolicyViolationException ----
class PolicyViolationException extends Exception {
    final StatementType statementType;

    PolicyViolationException(StatementType statementType, String message) {
        super(message);
        this.statementType = statementType;
    }
}

// ---- PolicyGuard ----
class PolicyGuard {
    private final boolean allowWrites;

    PolicyGuard(boolean allowWrites) { this.allowWrites = allowWrites; }

    void enforce(StatementType type) throws PolicyViolationException {
        if (type.isDdl()) {
            throw new PolicyViolationException(type,
                "DDL statements (CREATE, DROP, ALTER, TRUNCATE) are not allowed.");
        }
        if (!allowWrites && type.isWriteDml()) {
            throw new PolicyViolationException(type,
                "Write operations are disabled. Set allow-writes=true in ~/.pgcheck.properties to enable.");
        }
    }
}

// ---- ColumnInfo ----
record ColumnInfo(String name, String type) {}

// ---- QueryResult ----
sealed interface QueryResult permits SelectResult, DmlResult {}

record SelectResult(
    StatementType statementType,
    List<ObjectNode> rows,
    List<ColumnInfo> columns,
    boolean truncated,
    long durationMs
) implements QueryResult {}

record DmlResult(
    StatementType statementType,
    int rowsAffected,
    long durationMs
) implements QueryResult {}

// ---- QueryExecutor ----
class QueryExecutor {
    private final String url;
    private final String username;
    private final String password;
    private final int timeout;
    private final int maxRows;
    private final ObjectMapper mapper = new ObjectMapper();

    QueryExecutor(String url, String username, String password, int timeout, int maxRows) {
        this.url = url;
        this.username = username;
        this.password = password;
        this.timeout = timeout;
        this.maxRows = maxRows;
    }

    QueryResult execute(String sql, StatementType type) throws SQLException {
        long start = System.currentTimeMillis();
        try (Connection conn = DriverManager.getConnection(url, username, password)) {
            conn.setAutoCommit(true);
            try (Statement stmt = conn.createStatement()) {
                stmt.setQueryTimeout(timeout);
                if (type.isRead()) {
                    return runSelect(stmt, sql, start);
                } else {
                    return runDml(stmt, sql, type, start);
                }
            }
        }
    }

    private SelectResult runSelect(Statement stmt, String sql, long start) throws SQLException {
        stmt.setMaxRows(maxRows + 1);
        boolean hasRs = stmt.execute(sql);
        long durationMs = System.currentTimeMillis() - start;
        if (!hasRs) {
            return new SelectResult(StatementType.SELECT, List.of(), List.of(), false, durationMs);
        }
        try (ResultSet rs = stmt.getResultSet()) {
            List<ColumnInfo> columns = collectColumns(rs.getMetaData());
            List<ObjectNode> rows = new ArrayList<>();
            boolean truncated = false;
            while (rs.next()) {
                if (rows.size() >= maxRows) { truncated = true; break; }
                rows.add(collectRow(rs, columns));
            }
            return new SelectResult(StatementType.SELECT, rows, columns, truncated, durationMs);
        }
    }

    private DmlResult runDml(Statement stmt, String sql, StatementType type, long start) throws SQLException {
        int affected = stmt.executeUpdate(sql);
        long durationMs = System.currentTimeMillis() - start;
        return new DmlResult(type, affected, durationMs);
    }

    private List<ColumnInfo> collectColumns(ResultSetMetaData meta) throws SQLException {
        int count = meta.getColumnCount();
        List<ColumnInfo> cols = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            cols.add(new ColumnInfo(meta.getColumnName(i), meta.getColumnTypeName(i)));
        }
        return cols;
    }

    private ObjectNode collectRow(ResultSet rs, List<ColumnInfo> columns) throws SQLException {
        ObjectNode row = mapper.createObjectNode();
        for (int i = 0; i < columns.size(); i++) {
            Object val = rs.getObject(i + 1);
            String name = columns.get(i).name();
            if (val == null) {
                row.putNull(name);
            } else if (val instanceof Boolean b) {
                row.put(name, b);
            } else if (val instanceof Integer n) {
                row.put(name, n);
            } else if (val instanceof Long n) {
                row.put(name, n);
            } else if (val instanceof Short n) {
                row.put(name, (int) n);
            } else if (val instanceof Byte n) {
                row.put(name, (int) n);
            } else if (val instanceof BigDecimal n) {
                row.put(name, n);
            } else if (val instanceof Float n) {
                row.put(name, n);
            } else if (val instanceof Double n) {
                row.put(name, n);
            } else if (val instanceof byte[] bytes) {
                var sb = new StringBuilder("\\x");
                for (byte b : bytes) sb.append(String.format("%02x", b));
                row.put(name, sb.toString());
            } else {
                row.put(name, val.toString());
            }
        }
        return row;
    }

    static boolean isConnectionError(SQLException e) {
        String state = e.getSQLState();
        if (state == null) return true;
        return state.startsWith("08");
    }
}

// ---- ResponseWriter ----
class ResponseWriter {
    private final ObjectMapper mapper = new ObjectMapper();

    void writeSuccess(QueryResult result) {
        ObjectNode node = mapper.createObjectNode();
        if (result instanceof SelectResult sr) {
            node.put("status", "ok");
            node.put("statement_type", sr.statementType().name());
            node.put("row_count", sr.rows().size());
            node.put("truncated", sr.truncated());
            ArrayNode cols = node.putArray("columns");
            for (ColumnInfo col : sr.columns()) {
                cols.addObject().put("name", col.name()).put("type", col.type());
            }
            ArrayNode rows = node.putArray("rows");
            sr.rows().forEach(rows::add);
            node.put("duration_ms", sr.durationMs());
        } else if (result instanceof DmlResult dr) {
            node.put("status", "ok");
            node.put("statement_type", dr.statementType().name());
            node.put("rows_affected", dr.rowsAffected());
            node.put("duration_ms", dr.durationMs());
        }
        emit(node);
    }

    void writeInputError(String message) {
        ObjectNode node = mapper.createObjectNode();
        node.put("status", "error");
        node.put("error_type", "input_error");
        node.put("message", message);
        emit(node);
    }

    void writePolicyViolation(PolicyViolationException e) {
        ObjectNode node = mapper.createObjectNode();
        node.put("status", "error");
        node.put("error_type", "policy_violation");
        node.put("message", e.getMessage());
        node.put("statement_type", e.statementType.name());
        emit(node);
    }

    void writeSqlError(SQLException e, long durationMs) {
        ObjectNode node = mapper.createObjectNode();
        node.put("status", "error");
        node.put("error_type", "sql_error");
        node.put("message", e.getMessage());
        node.put("sql_state", e.getSQLState() != null ? e.getSQLState() : "");
        node.put("duration_ms", durationMs);
        emit(node);
    }

    void writeConnectionError(SQLException e) {
        ObjectNode node = mapper.createObjectNode();
        node.put("status", "error");
        node.put("error_type", "connection_error");
        node.put("message", e.getMessage());
        emit(node);
    }

    private void emit(ObjectNode node) {
        try {
            System.out.println(mapper.writeValueAsString(node));
        } catch (Exception e) {
            Logger.error(e, "Failed to serialize response");
        }
    }
}
