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

import android.content.Context;
import android.support.annotation.Nullable;
import com.facebook.stetho.InspectorModulesProvider;
import com.facebook.stetho.Stetho;
import com.facebook.stetho.inspector.protocol.ChromeDevtoolsDomain;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class StethoRealmInspectorModulesProvider implements InspectorModulesProvider {
    private final Context applicationContext;
    private final InspectorModulesProvider baseProvider;
    private final File[] dirs;

    StethoRealmInspectorModulesProvider(Context applicationContext,
        InspectorModulesProvider baseProvider, File[] dirs) {
        this.applicationContext = applicationContext;
        this.baseProvider = baseProvider;
        this.dirs = dirs;
    }

    @Override
    public Iterable<ChromeDevtoolsDomain> get() {
        final List<ChromeDevtoolsDomain> modules = new ArrayList<>();

        final Iterable<ChromeDevtoolsDomain> base = baseProvider.get();
        if (base != null) {
            for (ChromeDevtoolsDomain domain : base) {
                modules.add(domain);
            }
        }

        modules.add(new Database(applicationContext.getPackageName(), dirs));

        return modules;
    }

    public static final class Builder {
        private final Context applicationContext;

        @Nullable
        private InspectorModulesProvider baseProvider;

        @Nullable
        private File[] dirs;

        public Builder(Context context) {
            applicationContext = context.getApplicationContext();
        }

        public Builder baseProvider(@Nullable InspectorModulesProvider baseProvider) {
            this.baseProvider = baseProvider;
            return this;
        }

        public Builder dirs(@Nullable File... dirs) {
            this.dirs = dirs;
            return this;
        }

        public StethoRealmInspectorModulesProvider build() {
            if (baseProvider == null) {
                baseProvider = Stetho.defaultInspectorModulesProvider(applicationContext);
            }
            if (dirs == null || dirs.length == 0) {
                dirs = new File[] { applicationContext.getFilesDir() };
            }
            return new StethoRealmInspectorModulesProvider(applicationContext, baseProvider, dirs);
        }
    }
}
