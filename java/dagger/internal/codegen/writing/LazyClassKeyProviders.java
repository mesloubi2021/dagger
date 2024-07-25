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

package dagger.internal.codegen.writing;

import static dagger.internal.codegen.base.MapKeyAccessibility.isMapKeyAccessibleFrom;
import static javax.lang.model.element.Modifier.FINAL;
import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.element.Modifier.STATIC;

import androidx.room.compiler.processing.XAnnotation;
import com.google.common.base.Preconditions;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.TypeSpec;
import dagger.internal.codegen.base.UniqueNameSet;
import dagger.internal.codegen.javapoet.TypeNames;
import dagger.internal.codegen.model.Key;
import dagger.internal.codegen.writing.ComponentImplementation.ShardImplementation;
import java.util.HashMap;
import java.util.Map;

/** Keeps track of all providers for DaggerMap keys. */
public final class LazyClassKeyProviders {
  public static final String MAP_KEY_PROVIDER_NAME = "LazyClassKeyProvider";
  private final ClassName mapKeyProviderType;
  private final Map<Key, FieldSpec> entries = new HashMap<>();
  private final Map<Key, FieldSpec> keepClassNamesFields = new HashMap<>();
  private final UniqueNameSet uniqueFieldNames = new UniqueNameSet();
  private final ShardImplementation shardImplementation;
  private boolean providerAdded = false;

  LazyClassKeyProviders(ShardImplementation shardImplementation) {
    String name = shardImplementation.getUniqueClassName(MAP_KEY_PROVIDER_NAME);
    mapKeyProviderType = shardImplementation.name().nestedClass(name);
    this.shardImplementation = shardImplementation;
  }

  /** Returns a reference to a field in LazyClassKeyProvider that corresponds to this binding. */
  CodeBlock getMapKeyExpression(Key key) {
    // This is for avoid generating empty LazyClassKeyProvider in codegen tests
    if (!providerAdded) {
      shardImplementation.addTypeSupplier(this::build);
      providerAdded = true;
    }
    if (!entries.containsKey(key)) {
      addField(key);
    }
    return CodeBlock.of("$T.$N", mapKeyProviderType, entries.get(key));
  }

  private void addField(Key key) {
    Preconditions.checkArgument(
        key.multibindingContributionIdentifier().isPresent()
            && key.multibindingContributionIdentifier()
                .get()
                .bindingMethod()
                .xprocessing()
                .hasAnnotation(TypeNames.LAZY_CLASS_KEY));
    XAnnotation lazyClassKeyAnnotation =
        key.multibindingContributionIdentifier()
            .get()
            .bindingMethod()
            .xprocessing()
            .getAnnotation(TypeNames.LAZY_CLASS_KEY);
    ClassName lazyClassKey =
        lazyClassKeyAnnotation.getAsType("value").getTypeElement().getClassName();
    entries.put(
        key,
        FieldSpec.builder(
                TypeNames.STRING,
                uniqueFieldNames.getUniqueName(lazyClassKey.canonicalName().replace('.', '_')))
            // TODO(b/217435141): Leave the field as non-final. We will apply @IdentifierNameString
            // on the field, which doesn't work well with static final fields.
            .addModifiers(STATIC)
            .initializer("$S", lazyClassKey.reflectionName())
            .build());
    // To be able to apply -includedescriptorclasses rule to keep the class names referenced by
    // LazyClassKey, we need to generate fields that uses those classes as type in
    // LazyClassKeyProvider. For types that are not accessible from the generated component, we
    // generate fields in the proxy class.
    // Note: the generated field should not be initialized to avoid class loading.
    if (isMapKeyAccessibleFrom(lazyClassKeyAnnotation, shardImplementation.name().packageName())) {
      keepClassNamesFields.put(
          key,
          FieldSpec.builder(
                  lazyClassKey,
                  uniqueFieldNames.getUniqueName(lazyClassKey.canonicalName().replace('.', '_')))
              .addAnnotation(TypeNames.KEEP_FIELD_TYPE)
              .build());
    }
  }

  private TypeSpec build() {
    TypeSpec.Builder builder =
        TypeSpec.classBuilder(mapKeyProviderType)
            .addAnnotation(TypeNames.IDENTIFIER_NAME_STRING)
            .addModifiers(PRIVATE, STATIC, FINAL)
            .addFields(entries.values())
            .addFields(keepClassNamesFields.values());
    return builder.build();
  }
}
