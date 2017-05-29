/*
 * Copyright (C) 2017 Xizhi Zhu
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.xizzhu.stetho.realm;

import android.text.TextUtils;
import com.facebook.stetho.inspector.helper.ChromePeerManager;
import com.facebook.stetho.inspector.helper.PeerRegistrationListener;
import com.facebook.stetho.inspector.jsonrpc.JsonRpcPeer;
import com.facebook.stetho.inspector.jsonrpc.JsonRpcResult;
import com.facebook.stetho.inspector.protocol.ChromeDevtoolsDomain;
import com.facebook.stetho.inspector.protocol.ChromeDevtoolsMethod;
import com.facebook.stetho.json.ObjectMapper;
import com.facebook.stetho.json.annotation.JsonProperty;
import io.realm.RealmConfiguration;
import io.realm.internal.CheckedRow;
import io.realm.internal.SharedRealm;
import io.realm.internal.Table;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/**
 * NOTE The name has to be Database, otherwise Stetho won't be happy.
 */
final class Database implements ChromeDevtoolsDomain, PeerRegistrationListener {
    private final ChromePeerManager peerManager = new ChromePeerManager();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, SharedRealm> realms = new HashMap<>();
    private final String packageName;
    private final File[] dirs;
    private final Pattern namePattern;
    private final Map<String, byte[]> encryptionKeys;

    Database(String packageName, File[] dirs, Pattern namePattern,
        Map<String, byte[]> encryptionKeys) {
        this.packageName = packageName;
        this.dirs = dirs;
        this.namePattern = namePattern;
        this.encryptionKeys = encryptionKeys;
        peerManager.setListener(this);
    }

    @ChromeDevtoolsMethod
    public void enable(JsonRpcPeer peer, JSONObject params) {
        peerManager.addPeer(peer);
    }

    @ChromeDevtoolsMethod
    public void disable(JsonRpcPeer peer, JSONObject params) {
        peerManager.removePeer(peer);
    }

    @ChromeDevtoolsMethod
    public JsonRpcResult getDatabaseTableNames(JsonRpcPeer peer, JSONObject params) {
        final SharedRealm realm = getRealm(
            objectMapper.convertValue(params, GetDatabaseTableNamesRequest.class).databaseId);
        final int size = (int) realm.size();
        final List<String> tableNames = new ArrayList<>(size);
        for (int i = 0; i < size; ++i) {
            tableNames.add(realm.getTableName(i));
        }

        final GetDatabaseTableNamesResponse response = new GetDatabaseTableNamesResponse();
        response.tableNames = tableNames;
        return response;
    }

    private synchronized SharedRealm getRealm(String path) {
        SharedRealm realm = realms.get(path);
        if (realm == null) {
            final File realmFile = new File(path);
            final RealmConfiguration.Builder builder =
                new RealmConfiguration.Builder().directory(realmFile.getParentFile())
                    .name(realmFile.getName());
            final byte[] encryptionKey = encryptionKeys.get(realmFile.getName());
            if (encryptionKey != null && encryptionKey.length > 0) {
                builder.encryptionKey(encryptionKey);
            }
            realm = SharedRealm.getInstance(builder.build());
            realms.put(path, realm);
        }
        return realm;
    }

    private static final Pattern SELECT_PATTERN =
        Pattern.compile("SELECT ((\\w+, ?)*\\w+) FROM \"?(\\w+)\"?", Pattern.CASE_INSENSITIVE);
    private static final Pattern SELECT_ALL_PATTERN =
        Pattern.compile("SELECT (rowid, ?)?\\* FROM \"?(\\w+)\"?", Pattern.CASE_INSENSITIVE);

    @ChromeDevtoolsMethod
    public JsonRpcResult executeSQL(JsonRpcPeer peer, JSONObject params) {
        try {
            final ExecuteSQLRequest request =
                objectMapper.convertValue(params, ExecuteSQLRequest.class);
            final SharedRealm realm = getRealm(request.databaseId);
            String tableName = null;
            final List<String> columnNames = new ArrayList<>();
            final String query = request.query.replaceAll("\\s+", " ").trim();
            final Matcher selectMatcher = SELECT_PATTERN.matcher(query);
            if (selectMatcher.matches()) {
                tableName = selectMatcher.group(3);
                columnNames.addAll(
                    Arrays.asList(selectMatcher.group(1).replaceAll("\\s+", "").split(",")));
            } else {
                final Matcher selectAllMatcher = SELECT_ALL_PATTERN.matcher(query);
                if (selectAllMatcher.matches()) {
                    tableName = selectAllMatcher.group(2);

                    final Table table = realm.getTable(tableName);
                    final long columns = table.getColumnCount();
                    for (long i = 0L; i < columns; ++i) {
                        columnNames.add(table.getColumnName(i));
                    }
                }
            }
            if (TextUtils.isEmpty(tableName)) {
                final ExecuteSQLResponse response = new ExecuteSQLResponse();
                final Error error = new Error();
                error.message = "Query not supported";
                response.sqlError = error;
                return response;
            }
            columnNames.add(0, "rowid");

            final Table table = realm.getTable(tableName);
            final long columns = table.getColumnCount();
            final long rows = table.size();
            final List<String> values = new ArrayList<>();
            for (long row = 0L; row < rows; ++row) {
                // first column is "rowid"
                values.add(Long.toString(row));

                final CheckedRow checkedRow = table.getCheckedRow(row);
                for (long column = 0L; column < columns; ++column) {
                    values.add(formatColumn(checkedRow, column, table));
                }
            }

            final ExecuteSQLResponse response = new ExecuteSQLResponse();
            response.columnNames = columnNames;
            response.values = values;
            return response;
        } catch (Exception e) {
            final ExecuteSQLResponse response = new ExecuteSQLResponse();
            final Error error = new Error();
            error.message = e.getMessage();
            response.sqlError = error;
            return response;
        }
    }

    private static String formatColumn(CheckedRow checkedRow, long column, Table table) {
        if (checkedRow.isNull(column) || checkedRow.isNullLink(column)) {
            return "<null>";
        }
        switch (checkedRow.getColumnType(column)) {
            case BINARY:
                return Arrays.toString(checkedRow.getBinaryByteArray(column));
            case BOOLEAN:
                return Boolean.toString(checkedRow.getBoolean(column));
            case DATE:
                return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.ENGLISH).format(
                    checkedRow.getDate(column));
            case DOUBLE:
                return Double.toString(checkedRow.getDouble(column));
            case FLOAT:
                return Float.toString(checkedRow.getFloat(column));
            case INTEGER:
                return Long.toString(checkedRow.getLong(column));
            case LINKING_OBJECTS:
                return "<linking objects>";
            case LIST:
                return checkedRow.getLinkList(column).toString();
            case OBJECT:
                return formatObject(checkedRow, column, table);
            case STRING:
                return checkedRow.getString(column);
            case UNSUPPORTED_DATE:
                return "<unsupported date>";
            case UNSUPPORTED_MIXED:
                return "<unsupported mixed>";
            case UNSUPPORTED_TABLE:
                return "<unsupported table>";
            default:
                return "<unsupported>";
        }
    }

    private static String formatObject(CheckedRow checkedRow, long column, Table table) {
        final Table target = table.getLinkTarget(column);
        final StringBuilder builder = new StringBuilder().append(target.getClassName());
        if (target.hasPrimaryKey()) {
            final long primaryKeyColumn = target.getPrimaryKey();
            builder.append('<')
                .append(target.getColumnName(primaryKeyColumn))
                .append(": ")
                .append(
                    formatColumn(target.getCheckedRow(checkedRow.getLink(column)), primaryKeyColumn,
                        target))
                .append('>');
        }
        return builder.toString();
    }

    @Override
    public void onPeerRegistered(JsonRpcPeer jsonRpcPeer) {
        for (File dir : dirs) {
            if (!dir.isDirectory() || !dir.canRead()) {
                continue;
            }
            for (File file : dir.listFiles()) {
                if (!file.isFile() || !file.canRead() || !namePattern.matcher(file.getName())
                    .matches()) {
                    continue;
                }
                final DatabaseObject databaseParams = new DatabaseObject();
                databaseParams.id = file.getAbsolutePath();
                databaseParams.name = file.getName();
                databaseParams.domain = packageName;
                databaseParams.version = "N/A";
                final AddDatabaseEvent eventParams = new AddDatabaseEvent();
                eventParams.database = databaseParams;
                jsonRpcPeer.invokeMethod("Database.addDatabase", eventParams, null);
            }
        }
    }

    @Override
    public void onPeerUnregistered(JsonRpcPeer jsonRpcPeer) {
        for (Map.Entry<String, SharedRealm> entry : realms.entrySet()) {
            entry.getValue().close();
        }
        realms.clear();
    }

    static class AddDatabaseEvent {
        @JsonProperty(required = true)
        public DatabaseObject database;
    }

    static class DatabaseObject {
        @JsonProperty(required = true)
        public String id;

        @JsonProperty(required = true)
        public String domain;

        @JsonProperty(required = true)
        public String name;

        @JsonProperty(required = true)
        public String version;
    }

    static class GetDatabaseTableNamesRequest {
        @JsonProperty(required = true)
        public String databaseId;
    }

    static class GetDatabaseTableNamesResponse implements JsonRpcResult {
        @JsonProperty(required = true)
        public List<String> tableNames;
    }

    static class ExecuteSQLRequest {
        @JsonProperty(required = true)
        public String databaseId;

        @JsonProperty(required = true)
        public String query;
    }

    static class ExecuteSQLResponse implements JsonRpcResult {
        @JsonProperty
        public List<String> columnNames;

        @JsonProperty
        public List<String> values;

        @JsonProperty
        public Error sqlError;
    }

    public static class Error {
        @JsonProperty(required = true)
        public String message;

        @JsonProperty(required = true)
        public int code;
    }
}
