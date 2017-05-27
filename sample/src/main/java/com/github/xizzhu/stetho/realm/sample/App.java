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

package com.github.xizzhu.stetho.realm.sample;

import android.app.Application;
import com.facebook.stetho.Stetho;
import com.github.xizzhu.stetho.realm.StethoRealmInspectorModulesProvider;
import io.realm.Realm;
import io.realm.RealmConfiguration;
import java.io.File;

public final class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

        Realm.init(this);
        Stetho.initialize(Stetho.newInitializerBuilder(this)
            .enableDumpapp(Stetho.defaultDumperPluginsProvider(this))
            .enableWebKitInspector(
                new StethoRealmInspectorModulesProvider.Builder(this).dirs(getFilesDir(),
                    new File(getFilesDir(), "custom")).build())
            .build());

        populateRealm();
        populateRealm2();
        populateRealmIgnored();
    }

    private void populateRealm() {
        final Realm realm = Realm.getDefaultInstance();

        final Author moses = new Author();
        moses.name = "Moses";
        final Book genesis = new Book();
        genesis.index = 0;
        genesis.name = "Genesis";
        genesis.author = moses;
        final Book exodus = new Book();
        exodus.index = 1;
        exodus.name = "Exodus";
        exodus.author = moses;
        final Book hebrews = new Book();
        hebrews.index = 57;
        hebrews.name = "Letter to the Hebrews";

        realm.beginTransaction();
        realm.copyToRealmOrUpdate(genesis);
        realm.copyToRealmOrUpdate(exodus);
        realm.copyToRealmOrUpdate(hebrews);
        realm.commitTransaction();

        realm.close();
    }

    private void populateRealm2() {
        final Realm realm = Realm.getInstance(
            new RealmConfiguration.Builder().directory(new File(getFilesDir(), "custom"))
                .name("random.realm")
                .build());

        final Author moses = new Author();
        moses.name = "Moses";
        final Book genesis = new Book();
        genesis.index = 0;
        genesis.name = "Genesis";
        genesis.author = moses;
        final Verse verse =
            Verse.create(genesis, 0, 0, "In the beginning God created the heaven and the earth.");

        realm.beginTransaction();
        realm.copyToRealmOrUpdate(verse);
        realm.commitTransaction();

        realm.close();
    }

    private void populateRealmIgnored() {
        final Realm realm =
            Realm.getInstance(new RealmConfiguration.Builder().name("realm.ignored").build());

        final Author moses = new Author();
        moses.name = "Moses";
        final Book genesis = new Book();
        genesis.index = 0;
        genesis.name = "Genesis";
        genesis.author = moses;

        realm.beginTransaction();
        realm.copyToRealmOrUpdate(genesis);
        realm.commitTransaction();

        realm.close();
    }
}
