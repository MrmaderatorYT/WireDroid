package com.ccs.wiredroid.db;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/**
 * One saved connection.
 *
 * <p>{@link #password} is deliberately not part of {@link #toJson()} unless
 * {@link #savePassword} is set — see {@link DbConnectionStore} for why that
 * decision lives in the model rather than at the call site.</p>
 */
public final class DbConnection {

    public String id;
    public String name;
    public DbDrivers.Kind driver;
    public String host;
    public int port;
    /** Database name, or the absolute file path when {@link #driver} is SQLite. */
    public String database;
    public String user;
    /** Raw JDBC query fragment appended to the URL, e.g. {@code tcpKeepAlive=true}. */
    public String extraParams;
    public boolean useSsl;

    /** When false the password is never written to disk and is asked for on connect. */
    public boolean savePassword;
    /** In memory always; on disk only when {@link #savePassword}. */
    public String password;

    public DbConnection() {
        this.id = UUID.randomUUID().toString();
        this.driver = DbDrivers.Kind.POSTGRESQL;
        this.port = DbDrivers.Kind.POSTGRESQL.defaultPort;
        this.host = "";
        this.database = "";
        this.user = "";
        this.name = "";
        this.extraParams = "";
        this.password = "";
    }

    public DbConnection copy() {
        DbConnection c = new DbConnection();
        c.id = this.id;
        c.name = this.name;
        c.driver = this.driver;
        c.host = this.host;
        c.port = this.port;
        c.database = this.database;
        c.user = this.user;
        c.extraParams = this.extraParams;
        c.useSsl = this.useSsl;
        c.savePassword = this.savePassword;
        c.password = this.password;
        return c;
    }

    /** A copy with a fresh identity, for the Duplicate action. */
    public DbConnection duplicate(String newName) {
        DbConnection c = copy();
        c.id = UUID.randomUUID().toString();
        c.name = newName;
        return c;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("driver", driver.id);
        o.put("host", host);
        o.put("port", port);
        o.put("database", database);
        o.put("user", user);
        o.put("extra", extraParams);
        o.put("ssl", useSsl);
        o.put("savePassword", savePassword);
        // The single place a credential can reach disk, and only on request.
        if (savePassword) o.put("password", password == null ? "" : password);
        return o;
    }

    public static DbConnection fromJson(JSONObject o) {
        DbConnection c = new DbConnection();
        c.id = o.optString("id", UUID.randomUUID().toString());
        c.name = o.optString("name", "");
        c.driver = DbDrivers.Kind.byId(o.optString("driver", DbDrivers.Kind.SQLITE.id));
        c.host = o.optString("host", "");
        c.port = o.optInt("port", c.driver.defaultPort);
        c.database = o.optString("database", "");
        c.user = o.optString("user", "");
        c.extraParams = o.optString("extra", "");
        c.useSsl = o.optBoolean("ssl", false);
        c.savePassword = o.optBoolean("savePassword", false);
        c.password = c.savePassword ? o.optString("password", "") : "";
        return c;
    }
}
