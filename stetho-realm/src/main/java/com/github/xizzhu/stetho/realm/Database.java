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

import com.facebook.stetho.inspector.helper.ChromePeerManager;
import com.facebook.stetho.inspector.helper.PeerRegistrationListener;
import com.facebook.stetho.inspector.jsonrpc.JsonRpcPeer;
import com.facebook.stetho.inspector.jsonrpc.JsonRpcResult;
import com.facebook.stetho.inspector.protocol.ChromeDevtoolsDomain;
import com.facebook.stetho.inspector.protocol.ChromeDevtoolsMethod;
import com.facebook.stetho.json.ObjectMapper;
import com.facebook.stetho.json.annotation.JsonProperty;
import io.realm.DynamicRealm;
import io.realm.DynamicRealmObject;
import io.realm.RealmConfiguration;
import io.realm.RealmList;
import io.realm.RealmObjectSchema;
import io.realm.RealmSchema;
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
    private final Map<String, DynamicRealm> realms = new HashMap<>();
    private final String packageName;
    private final File[] dirs;

    Database(String packageName, File[] dirs) {
        this.packageName = packageName;
        this.dirs = dirs;
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
        final List<String> tableNames = new ArrayList<>();
        final DynamicRealm realm = getRealm(
            objectMapper.convertValue(params, GetDatabaseTableNamesRequest.class).databaseId);
        for (RealmObjectSchema schema : realm.getSchema().getAll()) {
            tableNames.add(schema.getClassName());
        }

        final GetDatabaseTableNamesResponse response = new GetDatabaseTableNamesResponse();
        response.tableNames = tableNames;
        return response;
    }

    private synchronized DynamicRealm getRealm(String path) {
        DynamicRealm realm = realms.get(path);
        if (realm == null) {
            final File realmFile = new File(path);
            realm = DynamicRealm.getInstance(
                new RealmConfiguration.Builder().directory(realmFile.getParentFile())
                    .name(realmFile.getName())
                    .build());
            realms.put(path, realm);
        }
        return realm;
    }

    private static final Pattern SELECT_ALL_PATTERN =
        Pattern.compile("SELECT[ \\t]+rowid,[ \\t]+\\*[ \\t]+FROM \"([^\"]+)\"");

    @ChromeDevtoolsMethod
    public JsonRpcResult executeSQL(JsonRpcPeer peer, JSONObject params) {
        final ExecuteSQLRequest request =
            objectMapper.convertValue(params, ExecuteSQLRequest.class);
        final String query = request.query.trim();
        final Matcher selectMatcher = SELECT_ALL_PATTERN.matcher(query);
        if (!selectMatcher.matches()) {
            return null;
        }
        final String tableName = selectMatcher.group(1);
        final DynamicRealm realm = getRealm(request.databaseId);
        final RealmSchema schema = realm.getSchema();
        final List<String> columnNames = new ArrayList<>(schema.get(tableName).getFieldNames());
        final List<String> values = new ArrayList<>();
        for (DynamicRealmObject object : realm.where(tableName).findAll()) {
            for (String columnName : columnNames) {
                values.add(formatColumn(object, columnName, schema));
            }
        }

        final ExecuteSQLResponse response = new ExecuteSQLResponse();
        response.columnNames = columnNames;
        response.values = values;
        return response;
    }

    private static String formatColumn(DynamicRealmObject object, String columnName,
        RealmSchema schema) {
        if (object.isNull(columnName)) {
            return "<null>";
        }
        switch (object.getFieldType(columnName)) {
            case BINARY:
                return Arrays.toString(object.getBlob(columnName));
            case BOOLEAN:
                return Boolean.toString(object.getBoolean(columnName));
            case DATE:
                return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.ENGLISH).format(
                    object.getDate(columnName));
            case DOUBLE:
                return Double.toString(object.getDouble(columnName));
            case FLOAT:
                return Float.toString(object.getFloat(columnName));
            case INTEGER:
                return Long.toString(object.getLong(columnName));
            case LINKING_OBJECTS:
                return "<linking objects>";
            case LIST:
                return formatList(object.getList(columnName), schema);
            case OBJECT:
                return formatObject(object.getObject(columnName), schema);
            case STRING:
                return object.getString(columnName);
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

    private static String formatList(RealmList<DynamicRealmObject> list, RealmSchema schema) {
        final StringBuilder builder = new StringBuilder();
        for (DynamicRealmObject object : list) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(formatObject(object, schema));
        }
        return builder.toString();
    }

    private static String formatObject(DynamicRealmObject object, RealmSchema schema) {
        final StringBuilder builder = new StringBuilder().append(object.getType());
        final RealmObjectSchema objectSchema = schema.get(object.getType());
        if (objectSchema.hasPrimaryKey()) {
            builder.append(" <")
                .append(objectSchema.getPrimaryKey())
                .append(':')
                .append(formatColumn(object, objectSchema.getPrimaryKey(), schema))
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
                if (!file.isFile() || !file.canRead() || !file.getName().endsWith(".realm")) {
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
        for (Map.Entry<String, DynamicRealm> entry : realms.entrySet()) {
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
