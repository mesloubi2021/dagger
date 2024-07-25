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

import static com.google.common.base.Preconditions.checkArgument;
import static com.squareup.javapoet.MethodSpec.constructorBuilder;
import static com.squareup.javapoet.MethodSpec.methodBuilder;
import static com.squareup.javapoet.TypeSpec.classBuilder;
import static dagger.internal.codegen.binding.AssistedInjectionAnnotations.assistedParameters;
import static dagger.internal.codegen.binding.SourceFiles.bindingTypeElementTypeVariableNames;
import static dagger.internal.codegen.binding.SourceFiles.generateBindingFieldsForDependencies;
import static dagger.internal.codegen.binding.SourceFiles.generatedClassNameForBinding;
import static dagger.internal.codegen.binding.SourceFiles.parameterizedGeneratedTypeNameForBinding;
import static dagger.internal.codegen.extension.DaggerStreams.presentValues;
import static dagger.internal.codegen.extension.DaggerStreams.toImmutableList;
import static dagger.internal.codegen.extension.DaggerStreams.toImmutableMap;
import static dagger.internal.codegen.javapoet.AnnotationSpecs.Suppression.RAWTYPES;
import static dagger.internal.codegen.javapoet.AnnotationSpecs.Suppression.UNCHECKED;
import static dagger.internal.codegen.javapoet.AnnotationSpecs.suppressWarnings;
import static dagger.internal.codegen.javapoet.CodeBlocks.parameterNames;
import static dagger.internal.codegen.javapoet.TypeNames.factoryOf;
import static dagger.internal.codegen.model.BindingKind.INJECTION;
import static dagger.internal.codegen.model.BindingKind.PROVISION;
import static dagger.internal.codegen.writing.GwtCompatibility.gwtIncompatibleAnnotation;
import static javax.lang.model.element.Modifier.FINAL;
import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.element.Modifier.PUBLIC;
import static javax.lang.model.element.Modifier.STATIC;

import androidx.room.compiler.processing.XElement;
import androidx.room.compiler.processing.XExecutableParameterElement;
import androidx.room.compiler.processing.XFiler;
import androidx.room.compiler.processing.XProcessingEnv;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import dagger.internal.codegen.base.SourceFileGenerator;
import dagger.internal.codegen.base.UniqueNameSet;
import dagger.internal.codegen.binding.AssistedInjectionBinding;
import dagger.internal.codegen.binding.ContributionBinding;
import dagger.internal.codegen.binding.InjectionBinding;
import dagger.internal.codegen.binding.MembersInjectionBinding.InjectionSite;
import dagger.internal.codegen.binding.ProvisionBinding;
import dagger.internal.codegen.binding.SourceFiles;
import dagger.internal.codegen.compileroption.CompilerOptions;
import dagger.internal.codegen.javapoet.TypeNames;
import dagger.internal.codegen.model.BindingKind;
import dagger.internal.codegen.model.DaggerAnnotation;
import dagger.internal.codegen.model.DependencyRequest;
import dagger.internal.codegen.model.Key;
import dagger.internal.codegen.model.Scope;
import dagger.internal.codegen.writing.InjectionMethods.InjectionSiteMethod;
import dagger.internal.codegen.writing.InjectionMethods.ProvisionMethod;
import java.util.Optional;
import java.util.stream.Stream;
import javax.inject.Inject;

/** Generates factory implementation for injection, assisted injection, and provision bindings. */
public final class FactoryGenerator extends SourceFileGenerator<ContributionBinding> {
  private static final ImmutableSet<BindingKind> VALID_BINDING_KINDS =
      ImmutableSet.of(BindingKind.INJECTION, BindingKind.ASSISTED_INJECTION, BindingKind.PROVISION);

  private final CompilerOptions compilerOptions;
  private final SourceFiles sourceFiles;

  @Inject
  FactoryGenerator(
      XFiler filer,
      CompilerOptions compilerOptions,
      SourceFiles sourceFiles,
      XProcessingEnv processingEnv) {
    super(filer, processingEnv);
    this.compilerOptions = compilerOptions;
    this.sourceFiles = sourceFiles;
  }

  @Override
  public XElement originatingElement(ContributionBinding binding) {
    // we only create factories for bindings that have a binding element
    return binding.bindingElement().get();
  }

  @Override
  public ImmutableList<TypeSpec.Builder> topLevelTypes(ContributionBinding binding) {
    // We don't want to write out resolved bindings -- we want to write out the generic version.
    checkArgument(!binding.unresolved().isPresent());
    checkArgument(binding.bindingElement().isPresent());
    checkArgument(VALID_BINDING_KINDS.contains(binding.kind()));

    return ImmutableList.of(factoryBuilder(binding));
  }

  private TypeSpec.Builder factoryBuilder(ContributionBinding binding) {
    TypeSpec.Builder factoryBuilder =
        classBuilder(generatedClassNameForBinding(binding))
            .addModifiers(PUBLIC, FINAL)
            .addTypeVariables(bindingTypeElementTypeVariableNames(binding))
            .addAnnotation(scopeMetadataAnnotation(binding))
            .addAnnotation(qualifierMetadataAnnotation(binding));

    factoryTypeName(binding).ifPresent(factoryBuilder::addSuperinterface);
    FactoryFields factoryFields = FactoryFields.create(binding);
    // If the factory has no input fields we can use a static instance holder to create a
    // singleton instance of the factory. Otherwise, we create a new instance via the constructor.
    if (factoryFields.isEmpty()) {
      factoryBuilder.addType(staticInstanceHolderType(binding));
    } else {
      factoryBuilder
          .addFields(factoryFields.getAll())
          .addMethod(constructorMethod(factoryFields));
    }
    gwtIncompatibleAnnotation(binding).ifPresent(factoryBuilder::addAnnotation);

    return factoryBuilder
        .addMethod(getMethod(binding, factoryFields))
        .addMethod(staticCreateMethod(binding, factoryFields))
        .addMethod(staticProvisionMethod(binding));
  }

  // private static final class InstanceHolder {
  //   private static final FooModule_ProvidesFooFactory INSTANCE =
  //       new FooModule_ProvidesFooFactory();
  // }
  private TypeSpec staticInstanceHolderType(ContributionBinding binding) {
    ClassName generatedClassName = generatedClassNameForBinding(binding);
    FieldSpec.Builder instanceHolderFieldBuilder =
        FieldSpec.builder(generatedClassName, "INSTANCE", PRIVATE, STATIC, FINAL)
            .initializer("new $T()", generatedClassName);
    if (!bindingTypeElementTypeVariableNames(binding).isEmpty()) {
      // If the factory has type parameters, ignore them in the field declaration & initializer
      instanceHolderFieldBuilder.addAnnotation(suppressWarnings(RAWTYPES));
    }
    return TypeSpec.classBuilder(instanceHolderClassName(binding))
        .addModifiers(PRIVATE, STATIC, FINAL)
        .addField(instanceHolderFieldBuilder.build())
        .build();
  }

  private static ClassName instanceHolderClassName(ContributionBinding binding) {
    return generatedClassNameForBinding(binding).nestedClass("InstanceHolder");
  }

  // public FooModule_ProvidesFooFactory(
  //     FooModule module,
  //     Provider<Bar> barProvider,
  //     Provider<Baz> bazProvider) {
  //   this.module = module;
  //   this.barProvider = barProvider;
  //   this.bazProvider = bazProvider;
  // }
  private MethodSpec constructorMethod(FactoryFields factoryFields) {
    // TODO(bcorso): Make the constructor private?
    MethodSpec.Builder constructor = constructorBuilder().addModifiers(PUBLIC);
    factoryFields.getAll().forEach(
        field ->
            constructor
                .addParameter(field.type, field.name)
                .addStatement("this.$1N = $1N", field));
    return constructor.build();
  }

  // Example 1: no dependencies.
  // public static FooModule_ProvidesFooFactory create() {
  //   return InstanceHolder.INSTANCE;
  // }
  //
  // Example 2: with dependencies.
  // public static FooModule_ProvidesFooFactory create(
  //     FooModule module,
  //     Provider<Bar> barProvider,
  //     Provider<Baz> bazProvider) {
  //   return new FooModule_ProvidesFooFactory(module, barProvider, bazProvider);
  // }
  private MethodSpec staticCreateMethod(ContributionBinding binding, FactoryFields factoryFields) {
    // We use a static create method so that generated components can avoid having to refer to the
    // generic types of the factory.  (Otherwise they may have visibility problems referring to the
    // types.)
    MethodSpec.Builder createMethodBuilder =
        methodBuilder("create")
            .addModifiers(PUBLIC, STATIC)
            .returns(parameterizedGeneratedTypeNameForBinding(binding))
            .addTypeVariables(bindingTypeElementTypeVariableNames(binding));

    if (factoryFields.isEmpty()) {
      if (!bindingTypeElementTypeVariableNames(binding).isEmpty()) {
        createMethodBuilder.addAnnotation(suppressWarnings(UNCHECKED));
      }
      createMethodBuilder.addStatement("return $T.INSTANCE", instanceHolderClassName(binding));
    } else {
      ImmutableList<ParameterSpec> parameters =
          factoryFields.getAll().stream()
              .map(field -> ParameterSpec.builder(field.type, field.name).build())
              .collect(toImmutableList());
      createMethodBuilder
          .addParameters(parameters)
          .addStatement(
              "return new $T($L)",
              parameterizedGeneratedTypeNameForBinding(binding),
              parameterNames(parameters));
    }
    return createMethodBuilder.build();
  }

  // Example 1: Provision binding.
  // @Override
  // public Foo get() {
  //   return provideFoo(module, barProvider.get(), bazProvider.get());
  // }
  //
  // Example 2: Injection binding with some inject field.
  // @Override
  // public Foo get() {
  //   Foo instance = newInstance(barProvider.get(), bazProvider.get());
  //   Foo_MembersInjector.injectSomeField(instance, someFieldProvider.get());
  //   return instance;
  // }
  private MethodSpec getMethod(ContributionBinding binding, FactoryFields factoryFields) {
    UniqueNameSet uniqueFieldNames = new UniqueNameSet();
    factoryFields.getAll().forEach(field -> uniqueFieldNames.claim(field.name));
    ImmutableMap<XExecutableParameterElement, ParameterSpec> assistedParameters =
        assistedParameters(binding).stream()
            .collect(
                toImmutableMap(
                    parameter -> parameter,
                    parameter ->
                        ParameterSpec.builder(
                                parameter.getType().getTypeName(),
                                uniqueFieldNames.getUniqueName(parameter.getJvmName()))
                            .build()));
    TypeName providedTypeName = providedTypeName(binding);
    MethodSpec.Builder getMethod =
        methodBuilder("get")
            .addModifiers(PUBLIC)
            .addParameters(assistedParameters.values());

    if (factoryTypeName(binding).isPresent()) {
      getMethod.addAnnotation(Override.class);
    }
    CodeBlock invokeNewInstance =
        ProvisionMethod.invoke(
            binding,
            request ->
                sourceFiles.frameworkTypeUsageStatement(
                    CodeBlock.of("$N", factoryFields.get(request)), request.kind()),
            param -> assistedParameters.get(param).name,
            generatedClassNameForBinding(binding),
            factoryFields.moduleField.map(module -> CodeBlock.of("$N", module)),
            compilerOptions);

    if (binding.kind().equals(PROVISION)) {
      binding
          .nullability()
          .nullableAnnotations()
          .forEach(getMethod::addAnnotation);
      getMethod.returns(providedTypeName);
      getMethod.addStatement("return $L", invokeNewInstance);
    } else if (!injectionSites(binding).isEmpty()) {
      CodeBlock instance = CodeBlock.of("instance");
      getMethod
          .returns(providedTypeName)
          .addStatement("$T $L = $L", providedTypeName, instance, invokeNewInstance)
          .addCode(
              InjectionSiteMethod.invokeAll(
                  injectionSites(binding),
                  generatedClassNameForBinding(binding),
                  instance,
                  binding.key().type().xprocessing(),
                  sourceFiles.frameworkFieldUsages(
                      binding.dependencies(), factoryFields.frameworkFields)::get))
          .addStatement("return $L", instance);

    } else {
      getMethod
          .returns(providedTypeName)
          .addStatement("return $L", invokeNewInstance);
    }
    return getMethod.build();
  }

  // Example 1: Provision binding
  // public static Foo provideFoo(FooModule module, Bar bar, Baz baz) {
  //   return Preconditions.checkNotNullFromProvides(module.provideFoo(bar, baz));
  // }
  //
  // Example 2: Injection binding
  // public static Foo newInstance(Bar bar, Baz baz) {
  //   return new Foo(bar, baz);
  // }
  private MethodSpec staticProvisionMethod(ContributionBinding binding) {
    return ProvisionMethod.create(binding, compilerOptions);
  }

  private AnnotationSpec scopeMetadataAnnotation(ContributionBinding binding) {
    AnnotationSpec.Builder builder = AnnotationSpec.builder(TypeNames.SCOPE_METADATA);
    binding.scope()
        .map(Scope::scopeAnnotation)
        .map(DaggerAnnotation::className)
        .map(ClassName::canonicalName)
        .ifPresent(scopeCanonicalName -> builder.addMember("value", "$S", scopeCanonicalName));
    return builder.build();
  }

  private AnnotationSpec qualifierMetadataAnnotation(ContributionBinding binding) {
    AnnotationSpec.Builder builder = AnnotationSpec.builder(TypeNames.QUALIFIER_METADATA);
    // Collect all qualifiers on the binding itself or its dependencies. For injection bindings, we
    // don't include the injection sites, as that is handled by MembersInjectorFactory.
    Stream.concat(
            Stream.of(binding.key()),
            provisionDependencies(binding).stream().map(DependencyRequest::key))
        .map(Key::qualifier)
        .flatMap(presentValues())
        .map(DaggerAnnotation::className)
        .map(ClassName::canonicalName)
        .distinct()
        .forEach(qualifier -> builder.addMember("value", "$S", qualifier));
    return builder.build();
  }

  private ImmutableSet<DependencyRequest> provisionDependencies(ContributionBinding binding) {
    switch (binding.kind()) {
      case INJECTION:
        return ((InjectionBinding) binding).constructorDependencies();
      case ASSISTED_INJECTION:
        return ((AssistedInjectionBinding) binding).constructorDependencies();
      case PROVISION:
        return ((ProvisionBinding) binding).dependencies();
      default:
        throw new AssertionError("Unexpected binding kind: " + binding.kind());
    }
  }

  private ImmutableSet<InjectionSite> injectionSites(ContributionBinding binding) {
    switch (binding.kind()) {
      case INJECTION:
        return ((InjectionBinding) binding).injectionSites();
      case ASSISTED_INJECTION:
        return ((AssistedInjectionBinding) binding).injectionSites();
      case PROVISION:
        return ImmutableSet.of();
      default:
        throw new AssertionError("Unexpected binding kind: " + binding.kind());
    }
  }

  private static TypeName providedTypeName(ContributionBinding binding) {
    return binding.contributedType().getTypeName();
  }

  private static Optional<TypeName> factoryTypeName(ContributionBinding binding) {
    return binding.kind() == BindingKind.ASSISTED_INJECTION
        ? Optional.empty()
        : Optional.of(factoryOf(providedTypeName(binding)));
  }

  /** Represents the available fields in the generated factory class. */
  private static final class FactoryFields {
    static FactoryFields create(ContributionBinding binding) {
      UniqueNameSet nameSet = new UniqueNameSet();
      // TODO(bcorso, dpb): Add a test for the case when a Factory parameter is named "module".
      Optional<FieldSpec> moduleField =
          binding.requiresModuleInstance()
              ? Optional.of(
                  createField(
                      binding.bindingTypeElement().get().getType().getTypeName(),
                      nameSet.getUniqueName("module")))
              : Optional.empty();

      ImmutableMap.Builder<DependencyRequest, FieldSpec> frameworkFields = ImmutableMap.builder();
      generateBindingFieldsForDependencies(binding).forEach(
          (dependency, field) ->
              frameworkFields.put(
                  dependency, createField(field.type(), nameSet.getUniqueName(field.name()))));

      return new FactoryFields(moduleField, frameworkFields.buildOrThrow());
    }

    private static FieldSpec createField(TypeName type, String name) {
      return FieldSpec.builder(type, name, PRIVATE, FINAL).build();
    }

    private final Optional<FieldSpec> moduleField;
    private final ImmutableMap<DependencyRequest, FieldSpec> frameworkFields;

    private FactoryFields(
        Optional<FieldSpec> moduleField,
        ImmutableMap<DependencyRequest, FieldSpec> frameworkFields) {
      this.moduleField = moduleField;
      this.frameworkFields = frameworkFields;
    }

    FieldSpec get(DependencyRequest request) {
      return frameworkFields.get(request);
    }

    ImmutableList<FieldSpec> getAll() {
      return moduleField.isPresent()
          ? ImmutableList.<FieldSpec>builder()
              .add(moduleField.get())
              .addAll(frameworkFields.values())
              .build()
          : frameworkFields.values().asList();
    }

    boolean isEmpty() {
      return getAll().isEmpty();
    }
  }
}
