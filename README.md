Stetho-Realm
============

[![API](https://img.shields.io/badge/API-9%2B-green.svg?style=flat)](https://developer.android.com/about/versions/android-2.3.html)
[![Release](https://jitpack.io/v/xizzhu/stetho-realm.svg)](https://jitpack.io/#xizzhu/stetho-realm)

A [Realm](https://realm.io/) module for [Stetho](https://github.com/facebook/stetho).

How to Use
----------

### Download
* Add the following to your `build.gradle`:
```gradle
repositories {
    maven { url "https://jitpack.io" }
}

dependencies {
    compile 'com.facebook.stetho:stetho:1.5.0'
    compile 'com.github.xizzhu:stetho-realm:0.1.3'
}
```

### Integration
````java
public final class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

        Realm.init(this);

        final StethoRealmInspectorModulesProvider inspectorModulesProvider =
            new StethoRealmInspectorModulesProvider.Builder(this)
                .dirs(new File(...))
                .namePattern(".+\\.realm")
                .encryptionKey("encrypted.realm", new byte[] {...})
                .build();
        Stetho.initialize(Stetho.newInitializerBuilder(this)
            .enableDumpapp(Stetho.defaultDumperPluginsProvider(this))
            .enableWebKitInspector(inspectorModulesProvider)
            .build());
    }
}
````

License
-------
    Copyright (C) 2017 Xizhi Zhu

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
