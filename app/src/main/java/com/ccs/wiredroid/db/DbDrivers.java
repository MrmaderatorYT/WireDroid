package com.ccs.wiredroid.db;

import android.content.Context;

import com.ccs.wiredroid.R;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Properties;

/**
 * What the bundled JDBC drivers are, how to build a URL for them, and what
 * they cannot do on Android.
 *
 * <p>{@code app/build.gradle} points here. Both drivers dex cleanly under D8
 * {@code --min-api 26}, but a JDBC driver is written for a full JRE and this
 * is not one. The classes {@code android.jar} does not provide are:</p>
 *
 * <ul>
 *   <li><b>JMX / {@code java.lang.management}</b> — driver-internal statistics
 *       registration. Never on a connect path.</li>
 *   <li><b>JNDI ({@code javax.naming})</b> — {@code DataSource} lookup. This
 *       client always builds a URL and calls {@link Driver#connect} itself, so
 *       the lookup path is never entered.</li>
 *   <li><b>Kerberos / GSSAPI</b> — only for {@code gssEncMode}/{@code
 *       authenticationPlugins} that we never request.</li>
 *   <li><b>XA transactions</b> — {@code javax.transaction.xa}. Not used.</li>
 *   <li><b>{@code java.sql.SQLType}</b> — so nothing here calls
 *       {@code setObject(int, Object, SQLType)}.</li>
 *   <li><b>{@code java.awt.Point}</b> — PostgreSQL's {@code PGpoint} extends
 *       it. {@link JdbcSession} reads every column with {@code getString}, so
 *       no geometric type is ever materialised.</li>
 * </ul>
 *
 * <p><b>The one that matters.</b> Both drivers verify TLS host names through
 * {@code javax.naming.ldap.LdapName} and {@code Rdn} (subject-DN parsing), and
 * Android ships neither. A plain, non-SSL connection is unaffected. An SSL
 * connection may die with {@link NoClassDefFoundError} the moment the driver
 * touches its verifier, which is why {@link #describe} treats any
 * {@link LinkageError} in the cause chain as "SSL is unsupported here" rather
 * than letting a bare stack trace reach the user. There is no workaround
 * inside the app: connect without SSL, or tunnel over SSH/VPN.</p>
 */
public final class DbDrivers {

    private DbDrivers() {}

    /** Seconds we are willing to wait for a socket + handshake + login. */
    public static final int CONNECT_TIMEOUT_SECONDS = 15;
    /** Seconds a single read may stall before the driver gives up. */
    public static final int SOCKET_TIMEOUT_SECONDS = 120;
    /** Seconds a single statement may run before it is cancelled. */
    public static final int QUERY_TIMEOUT_SECONDS = 60;

    /** The three back ends this client speaks to. */
    public enum Kind {
        /** Local file, opened with {@code android.database.sqlite} — no JDBC. */
        SQLITE("sqlite", R.string.db_driver_sqlite, 0, null),
        /** Served by the MariaDB driver, which speaks the MySQL wire protocol. */
        MYSQL("mysql", R.string.db_driver_mysql, 3306, "org.mariadb.jdbc.Driver"),
        POSTGRESQL("postgresql", R.string.db_driver_postgresql, 5432, "org.postgresql.Driver");

        public final String id;
        public final int labelRes;
        public final int defaultPort;
        /** Null for {@link #SQLITE}, which needs no JDBC driver. */
        public final String driverClass;

        Kind(String id, int labelRes, int defaultPort, String driverClass) {
            this.id = id;
            this.labelRes = labelRes;
            this.defaultPort = defaultPort;
            this.driverClass = driverClass;
        }

        public boolean isFile() {
            return this == SQLITE;
        }

        public static Kind byId(String id) {
            for (Kind k : values()) {
                if (k.id.equals(id)) return k;
            }
            return SQLITE;
        }

        public String label(Context ctx) {
            return ctx.getString(labelRes);
        }
    }

    /**
     * Loads and instantiates the driver reflectively.
     *
     * <p>Reflection rather than a direct reference for two reasons: Android's
     * {@code DriverManager} does not run the {@code ServiceLoader} discovery a
     * JRE does, so the driver has to be constructed by hand anyway; and this
     * class then compiles whether or not the artifacts are on the classpath.</p>
     */
    public static Driver instantiate(Kind kind) throws Exception {
        Class<?> c = Class.forName(kind.driverClass);
        return (Driver) c.getDeclaredConstructor().newInstance();
    }

    /** Builds the JDBC URL, timeouts and SSL mode included. */
    public static String buildUrl(DbConnection c) {
        StringBuilder url = new StringBuilder();
        StringBuilder q = new StringBuilder();
        switch (c.driver) {
            case POSTGRESQL:
                url.append("jdbc:postgresql://").append(c.host).append(':').append(c.port)
                        .append('/').append(c.database);
                append(q, "connectTimeout", String.valueOf(CONNECT_TIMEOUT_SECONDS));
                append(q, "loginTimeout", String.valueOf(CONNECT_TIMEOUT_SECONDS));
                append(q, "socketTimeout", String.valueOf(SOCKET_TIMEOUT_SECONDS));
                // "require" encrypts without verifying the certificate. Anything
                // stricter reaches the LdapName-based verifier Android lacks.
                append(q, "sslmode", c.useSsl ? "require" : "disable");
                break;
            case MYSQL:
                url.append("jdbc:mariadb://").append(c.host).append(':').append(c.port)
                        .append('/').append(c.database);
                append(q, "connectTimeout", String.valueOf(CONNECT_TIMEOUT_SECONDS * 1000));
                append(q, "socketTimeout", String.valueOf(SOCKET_TIMEOUT_SECONDS * 1000));
                // "trust" is MariaDB's encrypt-but-do-not-verify mode.
                append(q, "sslMode", c.useSsl ? "trust" : "disable");
                break;
            case SQLITE:
            default:
                return c.database;
        }
        String extra = c.extraParams == null ? "" : c.extraParams.trim();
        if (!extra.isEmpty()) {
            append(q, null, extra.startsWith("&") ? extra.substring(1) : extra);
        }
        if (q.length() > 0) url.append('?').append(q);
        return url.toString();
    }

    private static void append(StringBuilder q, String key, String value) {
        if (q.length() > 0) q.append('&');
        if (key != null) q.append(key).append('=');
        q.append(value);
    }

    /** User and password only; everything else travels in the URL. */
    public static Properties properties(DbConnection c, String password) {
        Properties p = new Properties();
        if (c.user != null && !c.user.isEmpty()) p.setProperty("user", c.user);
        p.setProperty("password", password == null ? "" : password);
        return p;
    }

    /**
     * Turns whatever the driver threw into one sentence a user can act on.
     *
     * <p>Order matters: a linkage failure is checked first because it arrives
     * wrapped in an ordinary {@link SQLException} and would otherwise be
     * reported as a generic connection error.</p>
     */
    public static String describe(Context ctx, DbConnection c, Throwable t) {
        if (t == null) return ctx.getString(R.string.db_err_generic, "");

        Throwable linkage = findLinkage(t);
        if (linkage != null) {
            return ctx.getString(R.string.db_err_ssl_unsupported, shortMessage(linkage));
        }

        Throwable network = find(t, UnknownHostException.class);
        if (network != null) {
            return ctx.getString(R.string.db_err_unknown_host, c.host);
        }
        if (find(t, ConnectException.class) != null
                || find(t, NoRouteToHostException.class) != null
                || find(t, SocketTimeoutException.class) != null) {
            return ctx.getString(R.string.db_err_unreachable, c.host, c.port, shortMessage(t));
        }

        SQLException sql = (SQLException) find(t, SQLException.class);
        if (sql != null) {
            String state = sql.getSQLState() == null ? "" : sql.getSQLState();
            String message = shortMessage(sql);
            String lower = message.toLowerCase(Locale.ROOT);

            // 28xxx is the SQL-standard authorization class; both drivers use it.
            if (state.startsWith("28")
                    || lower.contains("password authentication failed")
                    || lower.contains("access denied for user")
                    || lower.contains("authentication plugin")) {
                return ctx.getString(R.string.db_err_auth, message);
            }
            // 3D000 = invalid_catalog_name (PostgreSQL); MySQL 1049 = unknown database.
            if ("3D000".equals(state) || sql.getErrorCode() == 1049
                    || lower.contains("unknown database")) {
                return ctx.getString(R.string.db_err_db_missing, c.database, message);
            }
            // Class 08 = connection exception.
            if (state.startsWith("08")) {
                return ctx.getString(R.string.db_err_unreachable, c.host, c.port, message);
            }
            if (!state.isEmpty()) {
                return ctx.getString(R.string.db_err_sql_state, state, message);
            }
            return ctx.getString(R.string.db_err_generic, message);
        }

        return ctx.getString(R.string.db_err_generic, shortMessage(t));
    }

    /**
     * Describes a failure that happened while a statement was running, where
     * host/port/database are already known to be fine.
     */
    public static String describeSql(Context ctx, Throwable t) {
        if (t == null) return ctx.getString(R.string.db_err_generic, "");
        Throwable linkage = findLinkage(t);
        if (linkage != null) {
            return ctx.getString(R.string.db_err_ssl_unsupported, shortMessage(linkage));
        }
        SQLException sql = (SQLException) find(t, SQLException.class);
        if (sql != null && sql.getSQLState() != null && !sql.getSQLState().isEmpty()) {
            return ctx.getString(R.string.db_err_sql_state, sql.getSQLState(), shortMessage(sql));
        }
        return ctx.getString(R.string.db_err_generic, shortMessage(t));
    }

    /**
     * A missing class only ever means one thing here: the driver reached a
     * code path that needs a JRE class Android does not have, and on a connect
     * that path is TLS host name verification.
     */
    static Throwable findLinkage(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof LinkageError || c instanceof ClassNotFoundException) return c;
            if (c.getCause() == c) break;
        }
        return null;
    }

    private static Throwable find(Throwable t, Class<?> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) return c;
            if (c.getCause() == c) break;
        }
        return null;
    }

    /** The message, or the class name when a driver throws without one. */
    static String shortMessage(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.trim().isEmpty()) return t.getClass().getSimpleName();
        m = m.trim();
        // Driver messages can carry a full URL and a stack-ish tail.
        int nl = m.indexOf('\n');
        if (nl > 0) m = m.substring(0, nl);
        return m.length() > 300 ? m.substring(0, 300) + "…" : m;
    }
}
