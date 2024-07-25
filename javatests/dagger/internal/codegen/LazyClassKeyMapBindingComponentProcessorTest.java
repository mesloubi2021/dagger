/*
 * Copyright (C) 2024 The Dagger Authors.
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

package dagger.internal.codegen;

import static com.google.testing.compile.CompilationSubject.assertThat;

import androidx.room.compiler.processing.util.Source;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import dagger.testing.compile.CompilerTests;
import dagger.testing.golden.GoldenFileRule;
import java.util.Collection;
import javax.tools.JavaFileObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class LazyClassKeyMapBindingComponentProcessorTest {
  @Parameters(name = "{0}")
  public static Collection<Object[]> parameters() {
    return CompilerMode.TEST_PARAMETERS;
  }

  @Rule public GoldenFileRule goldenFileRule = new GoldenFileRule();

  private final CompilerMode compilerMode;

  public LazyClassKeyMapBindingComponentProcessorTest(CompilerMode compilerMode) {
    this.compilerMode = compilerMode;
  }

  // Cannot convert to use ksp as this test relies on AutoAnnotationProcessor.
  @Test
  public void mapBindingsWithInaccessibleKeys() throws Exception {
    JavaFileObject mapKeys =
        JavaFileObjects.forSourceLines(
            "mapkeys.MapKeys",
            "package mapkeys;",
            "",
            "import dagger.MapKey;",
            "import dagger.multibindings.LazyClassKey;",
            "",
            "public class MapKeys {",
            "  @MapKey(unwrapValue = false)",
            "  public @interface ComplexKey {",
            "    Class<?>[] manyClasses();",
            "    Class<?> oneClass();",
            "    LazyClassKey annotation();",
            "  }",
            "",
            "  interface Inaccessible {}",
            "}");
    JavaFileObject moduleFile =
        JavaFileObjects.forSourceLines(
            "mapkeys.MapModule",
            "package mapkeys;",
            "",
            "import dagger.Binds;",
            "import dagger.Module;",
            "import dagger.Provides;",
            "import dagger.multibindings.LazyClassKey;",
            "import dagger.multibindings.IntoMap;",
            "import java.util.Map;",
            "import javax.inject.Provider;",
            "",
            "@Module",
            "public interface MapModule {",
            "  @Provides @IntoMap @LazyClassKey(MapKeys.Inaccessible.class)",
            "  static int classKey() { return 1; }",
            "",
            "  @Provides @IntoMap",
            "  @MapKeys.ComplexKey(",
            "    manyClasses = {java.lang.Object.class, java.lang.String.class},",
            "    oneClass = MapKeys.Inaccessible.class,",
            "    annotation = @LazyClassKey(java.lang.Object.class)",
            "  )",
            "  static int complexKeyWithInaccessibleValue() { return 1; }",
            "",
            "  @Provides @IntoMap",
            "  @MapKeys.ComplexKey(",
            "    manyClasses = {MapKeys.Inaccessible.class, java.lang.String.class},",
            "    oneClass = java.lang.String.class,",
            "    annotation = @LazyClassKey(java.lang.Object.class)",
            "  )",
            "  static int complexKeyWithInaccessibleArrayValue() { return 1; }",
            "",
            "  @Provides @IntoMap",
            "  @MapKeys.ComplexKey(",
            "    manyClasses = {java.lang.String.class},",
            "    oneClass = java.lang.String.class,",
            "    annotation = @LazyClassKey(MapKeys.Inaccessible.class)",
            "  )",
            "  static int complexKeyWithInaccessibleAnnotationValue() { return 1; }",
            "}");
    JavaFileObject componentFile =
        JavaFileObjects.forSourceLines(
            "test.TestComponent",
            "package test;",
            "",
            "import dagger.Component;",
            "import java.util.Map;",
            "import javax.inject.Provider;",
            "import mapkeys.MapKeys;",
            "import mapkeys.MapModule;",
            "",
            "@Component(modules = MapModule.class)",
            "interface TestComponent {",
            "  Map<Class<?>, Integer> classKey();",
            "  Provider<Map<Class<?>, Integer>> classKeyProvider();",
            "",
            "  Map<MapKeys.ComplexKey, Integer> complexKey();",
            "  Provider<Map<MapKeys.ComplexKey, Integer>> complexKeyProvider();",
            "}");
    Compilation compilation =
        Compilers.compilerWithOptions(compilerMode.javacopts())
            .compile(mapKeys, moduleFile, componentFile);
    assertThat(compilation).succeeded();
    assertThat(compilation)
        .generatedSourceFile("test.DaggerTestComponent")
        .hasSourceEquivalentTo(goldenFileRule.goldenFile("test.DaggerTestComponent"));
    assertThat(compilation)
        .generatedSourceFile(
            "mapkeys.MapModule_ComplexKeyWithInaccessibleAnnotationValueMapKey")
        .hasSourceEquivalentTo(
            goldenFileRule.goldenFile(
                "mapkeys.MapModule_ComplexKeyWithInaccessibleAnnotationValueMapKey"));
    assertThat(compilation)
        .generatedSourceFile("mapkeys.MapModule_ClassKeyMapKey")
        .hasSourceEquivalentTo(goldenFileRule.goldenFile("mapkeys.MapModule_ClassKeyMapKey"));
  }

  @Test
  public void lazyClassKeySimilarQualifiedName_doesNotConflict() throws Exception {
    Source fooBar =
        CompilerTests.javaSource("test.Foo_Bar", "package test;", "", "interface Foo_Bar {}");
    Source fooBar2 =
        CompilerTests.javaSource(
            "test.Foo", "package test;", "", "interface Foo { interface Bar {} }");
    Source mapKeyBindingsModule =
        CompilerTests.javaSource(
            "test.MapKeyBindingsModule",
            "package test;",
            "",
            "import dagger.Module;",
            "import dagger.Provides;",
            "import dagger.multibindings.LazyClassKey;",
            "import dagger.multibindings.IntoMap;",
            "",
            "@Module",
            "public interface MapKeyBindingsModule {",
            " @Provides @IntoMap @LazyClassKey(test.Foo_Bar.class)",
            " static int classKey() { return 1; }",
            "",
            " @Provides @IntoMap @LazyClassKey(test.Foo.Bar.class)",
            " static int classKey2() { return 1; }",
            "}");

    Source componentFile =
        CompilerTests.javaSource(
            "test.TestComponent",
            "package test;",
            "",
            "import dagger.Component;",
            "import java.util.Map;",
            "",
            "@Component(modules = MapKeyBindingsModule.class)",
            "interface TestComponent {",
            "  Map<Class<?>, Integer> classKey();",
            "}");
    CompilerTests.daggerCompiler(fooBar, fooBar2, mapKeyBindingsModule, componentFile)
        .withProcessingOptions(compilerMode.processorOptions())
        .compile(subject -> subject.hasErrorCount(0));
  }

  @Test
  public void lazyClassKeyProvider_compilesSuccessfully() throws Exception {
    Source fooBar =
        CompilerTests.javaSource("test.Foo_Bar", "package test;", "", "interface Foo_Bar {}");
    Source fooBar2 =
        CompilerTests.javaSource(
            "test.Foo", "package test;", "", "interface Foo { interface Bar {} }");
    Source mapKeyBindingsModule =
        CompilerTests.javaSource(
            "test.MapKeyBindingsModule",
            "package test;",
            "",
            "import dagger.Module;",
            "import dagger.Provides;",
            "import dagger.multibindings.LazyClassKey;",
            "import dagger.multibindings.IntoMap;",
            "",
            "@Module",
            "public interface MapKeyBindingsModule {",
            " @Provides @IntoMap @LazyClassKey(test.Foo_Bar.class)",
            " static int classKey() { return 1; }",
            "}");

    Source componentFile =
        CompilerTests.javaSource(
            "test.TestComponent",
            "package test;",
            "",
            "import dagger.Component;",
            "import javax.inject.Provider;",
            "import java.util.Map;",
            "",
            "@Component(modules = MapKeyBindingsModule.class)",
            "interface TestComponent {",
            "  Map<Class<?>, Provider<Integer>> classKey();",
            "}");
    CompilerTests.daggerCompiler(fooBar, fooBar2, mapKeyBindingsModule, componentFile)
        .withProcessingOptions(compilerMode.processorOptions())
        .compile(subject -> subject.hasErrorCount(0));
  }

  @Test
  public void scopedLazyClassKeyProvider_compilesSuccessfully() throws Exception {
    Source fooBar =
        CompilerTests.javaSource("test.Foo_Bar", "package test;", "", "interface Foo_Bar {}");
    Source fooBar2 =
        CompilerTests.javaSource(
            "test.Foo", "package test;", "", "interface Foo { interface Bar {} }");
    Source mapKeyBindingsModule =
        CompilerTests.javaSource(
            "test.MapKeyBindingsModule",
            "package test;",
            "",
            "import dagger.Module;",
            "import dagger.Provides;",
            "import dagger.multibindings.LazyClassKey;",
            "import dagger.multibindings.IntoMap;",
            "",
            "@Module",
            "public interface MapKeyBindingsModule {",
            " @Provides @IntoMap @LazyClassKey(test.Foo_Bar.class)",
            " static int classKey() { return 1; }",
            "}");

    Source componentFile =
        CompilerTests.javaSource(
            "test.TestComponent",
            "package test;",
            "",
            "import dagger.Component;",
            "import javax.inject.Singleton;",
            "import javax.inject.Provider;",
            "import java.util.Map;",
            "",
            "@Component(modules = MapKeyBindingsModule.class)",
            "@Singleton",
            "interface TestComponent {",
            "  Provider<Map<Class<?>, Provider<Integer>>> classKey();",
            "}");
    CompilerTests.daggerCompiler(fooBar, fooBar2, mapKeyBindingsModule, componentFile)
        .withProcessingOptions(compilerMode.processorOptions())
        .compile(subject -> subject.hasErrorCount(0));
  }
}
