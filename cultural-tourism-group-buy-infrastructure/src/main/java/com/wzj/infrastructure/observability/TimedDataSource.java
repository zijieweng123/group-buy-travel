package com.wzj.infrastructure.observability;

import com.wzj.types.observability.TradeRequestTiming;
import com.wzj.types.observability.TradeRequestTiming.Stage;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import javax.sql.DataSource;
import java.sql.*;
import java.lang.reflect.*;

/** JDBC wall time includes transport and driver work; it is not MySQL server execution time. */
public final class TimedDataSource extends DelegatingDataSource implements AutoCloseable {
    public TimedDataSource(DataSource delegate) { super(delegate); }
    @Override public void close() throws Exception {
        DataSource delegate = getTargetDataSource();
        if (delegate instanceof AutoCloseable) ((AutoCloseable) delegate).close();
    }
    @Override public Connection getConnection() throws SQLException {
        return connection(TradeRequestTiming.call(Stage.POOL, () -> super.getConnection()));
    }
    @Override public Connection getConnection(String user, String password) throws SQLException {
        return connection(TradeRequestTiming.call(Stage.POOL, () -> super.getConnection(user, password)));
    }
    private Connection connection(Connection delegate) {
        if (TradeRequestTiming.current() == null) return delegate;
        return (Connection) proxy(delegate, Connection.class, true);
    }
    private static Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
        try { return method.invoke(target, arguments); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
    private static Object proxy(Object target, Class<?> api, boolean connection) {
        return Proxy.newProxyInstance(TimedDataSource.class.getClassLoader(), new Class<?>[]{api}, (self, method, args) -> {
            String name = method.getName();
            if (name.equals("unwrap") && ((Class<?>)args[0]).isInstance(self)) return self;
            if (name.equals("isWrapperFor") && ((Class<?>)args[0]).isInstance(self)) return true;
            Stage stage = null;
            if (connection) {
                if (name.startsWith("prepare") || name.equals("createStatement")) stage = Stage.JDBC_PREPARE;
                else if (name.equals("commit")) stage = Stage.COMMIT;
                else if (name.equals("rollback")) stage = Stage.ROLLBACK;
                else if (name.equals("close")) stage = Stage.JDBC_CLOSE;
                else if (name.equals("isReadOnly") || name.contains("AutoCommit") ||
                         name.contains("TransactionIsolation") || name.equals("setReadOnly")) stage = Stage.JDBC_STATE;
            } else if (name.startsWith("execute")) stage = Stage.SQL;
            long started = stage == null ? 0 : System.nanoTime();
            Object result;
            try { result = invoke(target, method, args); }
            finally { if (stage != null) TradeRequestTiming.record(stage, System.nanoTime()-started); }
            if (connection && result instanceof Statement) {
                Class<?> statementApi = result instanceof CallableStatement ? CallableStatement.class :
                        result instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
                return proxy(result, statementApi, false);
            }
            return result;
        });
    }
}
