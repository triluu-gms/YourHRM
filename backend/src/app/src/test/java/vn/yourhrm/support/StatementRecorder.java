package vn.yourhrm.support;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Test-only DataSource wrapper that records, for every transaction begun on a connection
 * ({@code setAutoCommit(false)}), the FIRST SQL statement prepared afterwards. Used to prove that the
 * tenant context ({@code set_config(...)}) is the first statement of every transaction (DESIGN.md §3.2).
 */
public final class StatementRecorder {

    private static final Queue<String> FIRST_STATEMENTS = new ConcurrentLinkedQueue<>();

    private StatementRecorder() {
    }

    public static void clear() {
        FIRST_STATEMENTS.clear();
    }

    public static List<String> firstStatements() {
        return List.copyOf(FIRST_STATEMENTS);
    }

    public static BeanPostProcessor postProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource ds && "dataSource".equals(beanName)) {
                    return new RecordingDataSource(ds);
                }
                return bean;
            }
        };
    }

    static final class RecordingDataSource extends DelegatingDataSource {
        RecordingDataSource(DataSource target) {
            super(target);
        }

        @Override
        public Connection getConnection() throws SQLException {
            return wrap(super.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return wrap(super.getConnection(username, password));
        }

        private static Connection wrap(Connection target) {
            return (Connection) Proxy.newProxyInstance(StatementRecorder.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, new Handler(target));
        }
    }

    static final class Handler implements InvocationHandler {
        private final Connection target;
        private boolean awaitingFirst;

        Handler(Connection target) {
            this.target = target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("setAutoCommit".equals(name) && Boolean.FALSE.equals(args[0])) {
                awaitingFirst = true;
            } else if (awaitingFirst && ("prepareStatement".equals(name) || "prepareCall".equals(name))
                    && args != null && args.length > 0 && args[0] instanceof String sql) {
                FIRST_STATEMENTS.add(sql);
                awaitingFirst = false;
            } else if (awaitingFirst && "createStatement".equals(name)) {
                FIRST_STATEMENTS.add("<plain statement>");
                awaitingFirst = false;
            } else if ("unwrap".equals(name) && args[0] instanceof Class<?> c && c.isInstance(proxy)) {
                return proxy;
            }
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getTargetException();
            }
        }
    }
}
