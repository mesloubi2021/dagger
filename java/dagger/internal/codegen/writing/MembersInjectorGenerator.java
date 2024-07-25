/*
 * Copyright (C) 2014 The Dagger Authors.
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

import static com.google.common.base.Preconditions.checkState;
import static com.squareup.javapoet.MethodSpec.constructorBuilder;
import static com.squareup.javapoet.MethodSpec.methodBuilder;
import static com.squareup.javapoet.TypeSpec.classBuilder;
import static dagger.internal.codegen.binding.SourceFiles.bindingTypeElementTypeVariableNames;
import static dagger.internal.codegen.binding.SourceFiles.generateBindingFieldsForDependencies;
import static dagger.internal.codegen.binding.SourceFiles.membersInjectorNameForType;
import static dagger.internal.codegen.binding.SourceFiles.parameterizedGeneratedTypeNameForBinding;
import static dagger.internal.codegen.extension.DaggerStreams.presentValues;
import static dagger.internal.codegen.extension.DaggerStreams.toImmutableList;
import static dagger.internal.codegen.javapoet.AnnotationSpecs.Suppression.RAWTYPES;
import static dagger.internal.codegen.javapoet.AnnotationSpecs.suppressWarnings;
import static dagger.internal.codegen.javapoet.CodeBlocks.parameterNames;
import static dagger.internal.codegen.javapoet.CodeBlocks.toConcatenatedCodeBlock;
import static dagger.internal.codegen.javapoet.TypeNames.membersInjectorOf;
import static dagger.internal.codegen.javapoet.TypeNames.rawTypeName;
import static dagger.internal.codegen.langmodel.Accessibility.isTypeAccessibleFrom;
import static dagger.internal.codegen.writing.GwtCompatibility.gwtIncompatibleAnnotation;
import static javax.lang.model.element.Modifier.FINAL;
import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.element.Modifier.PUBLIC;
import static javax.lang.model.element.Modifier.STATIC;

import androidx.room.compiler.processing.XElement;
import androidx.room.compiler.processing.XFiler;
import androidx.room.compiler.processing.XProcessingEnv;
import androidx.room.compiler.processing.XType;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeVariableName;
import dagger.MembersInjector;
import dagger.internal.codegen.base.SourceFileGenerator;
import dagger.internal.codegen.base.UniqueNameSet;
import dagger.internal.codegen.binding.MembersInjectionBinding;
import dagger.internal.codegen.binding.SourceFiles;
import dagger.internal.codegen.javapoet.TypeNames;
import dagger.internal.codegen.model.DaggerAnnotation;
import dagger.internal.codegen.model.DependencyRequest;
import dagger.internal.codegen.model.Key;
import dagger.internal.codegen.writing.InjectionMethods.InjectionSiteMethod;
import javax.inject.Inject;

/**
 * Generates {@link MembersInjector} implementations from {@link MembersInjectionBinding} instances.
 */
public final class MembersInjectorGenerator extends SourceFileGenerator<MembersInjectionBinding> {
  private final SourceFiles sourceFiles;

  @Inject
  MembersInjectorGenerator(
      XFiler filer,
      SourceFiles sourceFiles,
      XProcessingEnv processingEnv) {
    super(filer, processingEnv);
    this.sourceFiles = sourceFiles;
  }

  @Override
  public XElement originatingElement(MembersInjectionBinding binding) {
    return binding.membersInjectedType();
  }

  @Override
  public ImmutableList<TypeSpec.Builder> topLevelTypes(MembersInjectionBinding binding) {

    // We don't want to write out resolved bindings -- we want to write out the generic version.
    checkState(
        !binding.unresolved().isPresent(),
        "tried to generate a MembersInjector for a binding of a resolved generic type: %s",
        binding);

    ClassName generatedTypeName = membersInjectorNameForType(binding.membersInjectedType());
    ImmutableList<TypeVariableName> typeParameters = bindingTypeElementTypeVariableNames(binding);
    ImmutableMap<DependencyRequest, FieldSpec> frameworkFields = frameworkFields(binding);
    TypeSpec.Builder injectorTypeBuilder =
        classBuilder(generatedTypeName)
            .addModifiers(PUBLIC, FINAL)
            .addTypeVariables(typeParameters)
            .addAnnotation(qualifierMetadataAnnotation(binding))
            .addSuperinterface(membersInjectorOf(binding.key().type().xprocessing().getTypeName()))
            .addFields(frameworkFields.values())
            .addMethod(constructor(frameworkFields))
            .addMethod(createMethod(binding, frameworkFields))
            .addMethod(injectMembersMethod(binding, frameworkFields))
            .addMethods(
                binding.injectionSites().stream()
                    .filter(
                        site -> site.enclosingTypeElement().equals(binding.membersInjectedType()))
                    .map(InjectionSiteMethod::create)
                    .collect(toImmutableList()));

    gwtIncompatibleAnnotation(binding).ifPresent(injectorTypeBuilder::addAnnotation);

    return ImmutableList.of(injectorTypeBuilder);
  }

  // MyClass(
  //     Provider<Dep1> dep1Provider,
  //     Provider<Dep2> dep2Provider,
  //     // Note: The raw type can happen if Dep3 is injected in a super type and not accessible to
  //     // the parent. Ideally, we would have passed in the parent MembersInjector instance itself
  //     // which would have avoided this situation, but doing it now would cause version skew.
  //     @SuppressWarnings("RAW_TYPE") Provider dep3Provider) {
  //   this.dep1Provider = dep1Provider;
  //   this.dep2Provider = dep2Provider;
  //   this.dep3Provider = dep3Provider;
  // }
  private MethodSpec constructor(ImmutableMap<DependencyRequest, FieldSpec> frameworkFields) {
    ImmutableList<ParameterSpec> dependencyParameters =
        frameworkFields.values().stream()
            .map(
                field ->
                    ParameterSpec.builder(field.type, field.name)
                        .addAnnotations(field.annotations)
                        .build())
            .collect(toImmutableList());
    return constructorBuilder()
        .addModifiers(PUBLIC)
        .addParameters(dependencyParameters)
        .addCode(
            dependencyParameters.stream()
                .map(parameter -> CodeBlock.of("this.$1N = $1N;", parameter))
                .collect(toConcatenatedCodeBlock()))
        .build();
  }

  // public static MyClass_MembersInjector create(
  //     Provider<Dep1> dep1Provider,
  //     Provider<Dep2> dep2Provider,
  //     // Note: The raw type can happen if Dep3 is injected in a super type and not accessible to
  //     // the parent. Ideally, we would have passed in the parent MembersInjector instance itself
  //     // which would have avoided this situation, but doing it now would cause version skew.
  //     @SuppressWarnings("RAW_TYPE") Provider dep3Provider) {
  //   return new MyClass_MembersInjector(dep1Provider, dep2Provider, dep3Provider);
  // }
  private MethodSpec createMethod(
      MembersInjectionBinding binding,
      ImmutableMap<DependencyRequest, FieldSpec> frameworkFields) {
    MethodSpec constructor = constructor(frameworkFields);
    // We use a static create method so that generated components can avoid having
    // to refer to the generic types of the factory.
    // (Otherwise they may have visibility problems referring to the types.)
    return methodBuilder("create")
        .addModifiers(PUBLIC, STATIC)
        .addTypeVariables(bindingTypeElementTypeVariableNames(binding))
        .returns(membersInjectorOf(binding.key().type().xprocessing().getTypeName()))
        .addParameters(constructor.parameters)
        .addStatement(
            "return new $T($L)",
            parameterizedGeneratedTypeNameForBinding(binding),
            parameterNames(constructor.parameters))
        .build();
  }

  // @Override
  // public void injectMembers(Thing instance) {
  //   injectDep1(instance, dep1Provider.get());
  //   injectSomeMethod(instance, dep2Provider.get());
  //   // This is a case where Dep3 is injected in the base class.
  //   MyBaseClass_MembersInjector.injectDep3(instance, dep3Provider.get());
  // }
  private MethodSpec injectMembersMethod(
      MembersInjectionBinding binding,
      ImmutableMap<DependencyRequest, FieldSpec> frameworkFields) {
    XType instanceType = binding.key().type().xprocessing();
    ImmutableMap<DependencyRequest, CodeBlock> dependencyCodeBlocks =
        sourceFiles.frameworkFieldUsages(binding.dependencies(), frameworkFields);
    return methodBuilder("injectMembers")
        .addModifiers(PUBLIC)
        .addAnnotation(Override.class)
        .addParameter(instanceType.getTypeName(), "instance")
        .addCode(
            InjectionSiteMethod.invokeAll(
                binding.injectionSites(),
                membersInjectorNameForType(binding.membersInjectedType()),
                CodeBlock.of("instance"),
                instanceType,
                dependencyCodeBlocks::get))
        .build();
  }

  private AnnotationSpec qualifierMetadataAnnotation(MembersInjectionBinding binding) {
    AnnotationSpec.Builder builder = AnnotationSpec.builder(TypeNames.QUALIFIER_METADATA);
    binding.injectionSites().stream()
        // filter out non-local injection sites. Injection sites for super types will be in their
        // own generated _MembersInjector class.
        .filter(
            injectionSite ->
                injectionSite.enclosingTypeElement().equals(binding.membersInjectedType()))
        .flatMap(injectionSite -> injectionSite.dependencies().stream())
        .map(DependencyRequest::key)
        .map(Key::qualifier)
        .flatMap(presentValues())
        .map(DaggerAnnotation::className)
        .map(ClassName::canonicalName)
        .distinct()
        .forEach(qualifier -> builder.addMember("value", "$S", qualifier));
    return builder.build();
  }

  private static ImmutableMap<DependencyRequest, FieldSpec> frameworkFields(
      MembersInjectionBinding binding) {
    UniqueNameSet fieldNames = new UniqueNameSet();
    ClassName membersInjectorTypeName = membersInjectorNameForType(binding.membersInjectedType());
    ImmutableMap.Builder<DependencyRequest, FieldSpec> builder = ImmutableMap.builder();
    generateBindingFieldsForDependencies(binding)
        .forEach(
            (request, bindingField) -> {
              // If the dependency type is not visible to this members injector, then use the raw
              // framework type for the field.
              boolean useRawFrameworkType =
                  !isTypeAccessibleFrom(
                      request.key().type().xprocessing(),
                      membersInjectorTypeName.packageName());
              TypeName fieldType =
                  useRawFrameworkType ? rawTypeName(bindingField.type()) : bindingField.type();
              String fieldName = fieldNames.getUniqueName(bindingField.name());
              FieldSpec field =
                  useRawFrameworkType
                      ? FieldSpec.builder(fieldType, fieldName, PRIVATE, FINAL)
                          .addAnnotation(suppressWarnings(RAWTYPES))
                          .build()
                      : FieldSpec.builder(fieldType, fieldName, PRIVATE, FINAL).build();
              builder.put(request, field);
            });
    return builder.buildOrThrow();
  }
}
