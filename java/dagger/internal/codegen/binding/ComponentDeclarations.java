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

package dagger.internal.codegen.binding;

import static dagger.internal.codegen.binding.SourceFiles.generatedMonitoringModuleName;

import androidx.room.compiler.processing.XProcessingEnv;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Multimaps;
import dagger.internal.codegen.base.DaggerSuperficialValidation;
import dagger.internal.codegen.javapoet.TypeNames;
import dagger.internal.codegen.model.Key;
import java.util.Optional;
import javax.inject.Inject;

/** Stores the bindings and declarations of a component by key. */
final class ComponentDeclarations {
  private final ImmutableSetMultimap<Key, ContributionBinding> bindings;
  private final ImmutableSetMultimap<Key, DelegateDeclaration> delegates;
  private final ImmutableSetMultimap<Key, MultibindingDeclaration> multibindings;
  private final ImmutableSetMultimap<Key, OptionalBindingDeclaration> optionalBindings;
  private final ImmutableSetMultimap<Key, SubcomponentDeclaration> subcomponents;
  private final ImmutableSetMultimap<Key, ContributionBinding> multibindingContributions;
  private final ImmutableSetMultimap<Key, DelegateDeclaration> delegateMultibindingContributions;

  private ComponentDeclarations(
      ImmutableSetMultimap<Key, ContributionBinding> bindings,
      ImmutableSetMultimap<Key, DelegateDeclaration> delegates,
      ImmutableSetMultimap<Key, MultibindingDeclaration> multibindings,
      ImmutableSetMultimap<Key, OptionalBindingDeclaration> optionalBindings,
      ImmutableSetMultimap<Key, SubcomponentDeclaration> subcomponents) {
    this.bindings = bindings;
    this.delegates = delegates;
    this.multibindings = multibindings;
    this.optionalBindings = optionalBindings;
    this.subcomponents = subcomponents;
    this.multibindingContributions = multibindingContributionsByMultibindingKey(bindings.values());
    this.delegateMultibindingContributions =
        multibindingContributionsByMultibindingKey(delegates.values());
  }

  ImmutableSet<ContributionBinding> bindings(Key key) {
    return bindings.get(key);
  }

  ImmutableSet<DelegateDeclaration> delegates(Key key) {
    return delegates.get(key);
  }

  ImmutableSet<DelegateDeclaration> delegateMultibindingContributions(Key key) {
    return delegateMultibindingContributions.get(key);
  }

  ImmutableSet<MultibindingDeclaration> multibindings(Key key) {
    return multibindings.get(key);
  }

  ImmutableSet<ContributionBinding> multibindingContributions(Key key) {
    return multibindingContributions.get(key);
  }

  ImmutableSet<OptionalBindingDeclaration> optionalBindings(Key key) {
    return optionalBindings.get(key);
  }

  ImmutableSet<SubcomponentDeclaration> subcomponents(Key key) {
    return subcomponents.get(key);
  }

  ImmutableSet<BindingDeclaration> allDeclarations() {
    return ImmutableSet.<BindingDeclaration>builder()
        .addAll(bindings.values())
        .addAll(delegates.values())
        .addAll(multibindings.values())
        .addAll(optionalBindings.values())
        .addAll(subcomponents.values())
        .build();
  }

  /**
   * A multimap of those {@code declarations} that are multibinding contribution declarations,
   * indexed by the key of the set or map to which they contribute.
   */
  private static <T extends BindingDeclaration>
      ImmutableSetMultimap<Key, T> multibindingContributionsByMultibindingKey(
          Iterable<T> declarations) {
    ImmutableSetMultimap.Builder<Key, T> builder = ImmutableSetMultimap.builder();
    for (T declaration : declarations) {
      if (declaration.key().multibindingContributionIdentifier().isPresent()) {
        builder.put(declaration.key().withoutMultibindingContributionIdentifier(), declaration);
      }
    }
    return builder.build();
  }

  static final class Factory {
    private final XProcessingEnv processingEnv;
    private final ModuleDescriptor.Factory moduleDescriptorFactory;

    @Inject
    Factory(
        XProcessingEnv processingEnv,
        ModuleDescriptor.Factory moduleDescriptorFactory) {
      this.processingEnv = processingEnv;
      this.moduleDescriptorFactory = moduleDescriptorFactory;
    }

    ComponentDeclarations create(
        Optional<ComponentDescriptor> parentDescriptor, ComponentDescriptor descriptor) {
      ImmutableSet.Builder<ContributionBinding> bindings = ImmutableSet.builder();
      ImmutableSet.Builder<DelegateDeclaration> delegates = ImmutableSet.builder();
      ImmutableSet.Builder<MultibindingDeclaration> multibindings = ImmutableSet.builder();
      ImmutableSet.Builder<OptionalBindingDeclaration> optionalBindings =ImmutableSet.builder();
      ImmutableSet.Builder<SubcomponentDeclaration> subcomponents = ImmutableSet.builder();

      bindings.addAll(descriptor.bindings());
      delegates.addAll(descriptor.delegateDeclarations());
      multibindings.addAll(descriptor.multibindingDeclarations());
      optionalBindings.addAll(descriptor.optionalBindingDeclarations());
      subcomponents.addAll(descriptor.subcomponentDeclarations());

      // Note: The implicit production modules are not included directly in the component descriptor
      // because we don't know whether to install them or not without knowing the parent component.
      for (ModuleDescriptor module : implicitProductionModules(descriptor, parentDescriptor)) {
        bindings.addAll(module.bindings());
        delegates.addAll(module.delegateDeclarations());
        multibindings.addAll(module.multibindingDeclarations());
        optionalBindings.addAll(module.optionalDeclarations());
        subcomponents.addAll(module.subcomponentDeclarations());
      }

      return new ComponentDeclarations(
          indexDeclarationsByKey(bindings.build()),
          indexDeclarationsByKey(delegates.build()),
          indexDeclarationsByKey(multibindings.build()),
          indexDeclarationsByKey(optionalBindings.build()),
          indexDeclarationsByKey(subcomponents.build()));
    }

    /**
     * Returns all the modules that should be installed in the component. For production components
     * and production subcomponents that have a parent that is not a production component or
     * subcomponent, also includes the production monitoring module for the component and the
     * production executor module.
     */
    private ImmutableSet<ModuleDescriptor> implicitProductionModules(
        ComponentDescriptor descriptor, Optional<ComponentDescriptor> parentDescriptor) {
      return shouldIncludeImplicitProductionModules(descriptor, parentDescriptor)
          ? ImmutableSet.of(
              moduleDescriptorFactory.create(
                  DaggerSuperficialValidation.requireTypeElement(
                      processingEnv, generatedMonitoringModuleName(descriptor.typeElement()))),
              moduleDescriptorFactory.create(
                  processingEnv.requireTypeElement(TypeNames.PRODUCTION_EXECTUTOR_MODULE)))
          : ImmutableSet.of();
    }

    private static boolean shouldIncludeImplicitProductionModules(
        ComponentDescriptor descriptor, Optional<ComponentDescriptor> parentDescriptor) {
      return descriptor.isProduction()
          && descriptor.isRealComponent()
          && (parentDescriptor.isEmpty() || !parentDescriptor.get().isProduction());
    }

    /** Indexes {@code bindingDeclarations} by {@link BindingDeclaration#key()}. */
    private static <T extends BindingDeclaration>
        ImmutableSetMultimap<Key, T> indexDeclarationsByKey(Iterable<T> declarations) {
      return ImmutableSetMultimap.copyOf(Multimaps.index(declarations, BindingDeclaration::key));
    }
  }
}
