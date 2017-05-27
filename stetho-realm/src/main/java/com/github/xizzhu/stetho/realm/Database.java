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
import io.realm.RealmObjectSchema;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
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
        final List<String> columnNames =
            new ArrayList<>(realm.getSchema().get(tableName).getFieldNames());
        final List<String> values = new ArrayList<>();
        for (DynamicRealmObject realmObject : realm.where(tableName).findAll()) {
            for (String columnName : columnNames) {
                if (realmObject.isNull(columnName)) {
                    values.add("<NULL>");
                    continue;
                }
                switch (realmObject.getFieldType(columnName)) {
                    case BINARY:
                        values.add("<Binary>");
                        break;
                    case BOOLEAN:
                        values.add(Boolean.toString(realmObject.getBoolean(columnName)));
                        break;
                    case DATE:
                        values.add(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ",
                            Locale.ENGLISH).format(realmObject.getDate(columnName)));
                        break;
                    case DOUBLE:
                        values.add(Double.toString(realmObject.getDouble(columnName)));
                        break;
                    case FLOAT:
                        values.add(Float.toString(realmObject.getFloat(columnName)));
                        break;
                    case INTEGER:
                        values.add(Long.toString(realmObject.getLong(columnName)));
                        break;
                    case LINKING_OBJECTS:
                        values.add("<Linking Objects>");
                        break;
                    case LIST:
                        values.add("<List>");
                        break;
                    case OBJECT:
                        values.add("<Object>");
                        break;
                    case STRING:
                        values.add(realmObject.getString(columnName));
                        break;
                    case UNSUPPORTED_DATE:
                        values.add("<Unsupported Date>");
                        break;
                    case UNSUPPORTED_MIXED:
                        values.add("<Unsupported Mixed>");
                        break;
                    case UNSUPPORTED_TABLE:
                        values.add("<Unsupported Table>");
                        break;
                }
            }
        }

        final ExecuteSQLResponse response = new ExecuteSQLResponse();
        response.columnNames = columnNames;
        response.values = values;
        return response;
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
