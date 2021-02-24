/*
 * Copyright (C) 2021 The Dagger Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dagger.hilt.android.simple;

import android.app.Application;
import dagger.hilt.EntryPoint;
import dagger.hilt.EntryPoints;
import dagger.hilt.InstallIn;
import dagger.hilt.android.EarlyEntryPoint;
import dagger.hilt.android.EarlyEntryPoints;
import dagger.hilt.components.SingletonComponent;
import javax.inject.Inject;
import javax.inject.Singleton;

/** A custom application used to test @EarlyEntryPoint */
public class BaseTestApplication extends Application {
  @EarlyEntryPoint
  @EntryPoint
  @InstallIn(SingletonComponent.class)
  interface EarlyFooEntryPoint {
    Foo earlyFoo();
  }

  @EntryPoint
  @InstallIn(SingletonComponent.class)
  interface FooEntryPoint {
    Foo lazyFoo();
  }

  @Singleton
  public static final class Foo {
    @Inject
    Foo() {}
  }

  private Foo earlyFoo;

  @Override
  public void onCreate() {
    super.onCreate();
    earlyFoo = EarlyEntryPoints.get(this, EarlyFooEntryPoint.class).earlyFoo();
  }

  public Foo earlyFoo() {
    return earlyFoo;
  }

  public Foo lazyEarlyFoo() {
    return EarlyEntryPoints.get(this, EarlyFooEntryPoint.class).earlyFoo();
  }

  public Foo lazyFoo() {
    return EntryPoints.get(this, FooEntryPoint.class).lazyFoo();
  }
}