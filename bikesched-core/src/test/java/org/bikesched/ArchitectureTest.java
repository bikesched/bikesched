package org.bikesched;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.members;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMember;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.bikesched.internal.Internals;
import org.bikesched.internal.Settings;
import org.junit.jupiter.api.Test;

/**
 * Rules of the package structure (see AGENTS.md): the API packages exported by the module reach the
 * implementation only through {@link Internals} (plus the dependency-free {@code internal.util}),
 * expose no implementation types, and the implementation packages only depend downwards.
 */
class ArchitectureTest {

  /** The packages exported by {@code module-info.java}. */
  private static final String[] API = {"org.bikesched", "org.bikesched.job", "org.bikesched.event"};

  private static final String INTERNAL = "org.bikesched.internal..";

  /** Helpers without dependencies on the rest of bikesched, used by the API and implementation. */
  private static final String UTIL = "org.bikesched.internal.util..";

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("org.bikesched");

  @Test
  void apiReachesTheImplementationOnlyThroughInternals() {
    noClasses()
        .that()
        .resideInAnyPackage(API)
        .should()
        .dependOnClassesThat(
            resideInAPackage(INTERNAL)
                .and(not(belongToAnyOf(Internals.class, Settings.class)))
                .and(not(resideInAPackage(UTIL))))
        .because("the API creates the implementation via Internals only (and shares the utilities)")
        .check(CLASSES);
  }

  @Test
  void apiSignaturesExposeNoInternalTypes() {
    members()
        .that()
        .areDeclaredInClassesThat()
        .resideInAnyPackage(API)
        .and()
        .areDeclaredInClassesThat()
        .arePublic()
        .should(notExposeInternalTypes())
        .check(CLASSES);
  }

  @Test
  void implementationPackagesOnlyDependDownwards() {
    layeredArchitecture()
        .consideringOnlyDependenciesInLayers()
        .layer("Node")
        .definedBy("org.bikesched.internal")
        .layer("NodeParts")
        .definedBy("org.bikesched.internal.node..")
        .layer("Json")
        .definedBy("org.bikesched.internal.json..")
        .layer("Job")
        .definedBy("org.bikesched.internal.job..")
        .layer("Event")
        .definedBy("org.bikesched.internal.event..")
        .layer("Database")
        .definedBy("org.bikesched.internal.database")
        .layer("Schema")
        .definedBy("org.bikesched.internal.database.schema..")
        .whereLayer("Node")
        .mayNotBeAccessedByAnyLayer()
        .whereLayer("NodeParts")
        .mayOnlyBeAccessedByLayers("Node")
        .whereLayer("Json")
        .mayOnlyBeAccessedByLayers("Node")
        .whereLayer("Job")
        .mayOnlyBeAccessedByLayers("Node")
        .whereLayer("Event")
        .mayOnlyBeAccessedByLayers("Node", "Job")
        .whereLayer("Database")
        .mayOnlyBeAccessedByLayers("Node", "NodeParts", "Job", "Event")
        .whereLayer("Schema")
        .mayOnlyBeAccessedByLayers("Database")
        .check(CLASSES);
  }

  @Test
  void utilitiesDependOnNothingElseOfBikesched() {
    noClasses()
        .that()
        .resideInAPackage(UTIL)
        .should()
        .dependOnClassesThat(resideInAPackage("org.bikesched..").and(not(resideInAPackage(UTIL))))
        .because("the utilities are shared by the API and every implementation package")
        .check(CLASSES);
  }

  /**
   * Public and protected members must not mention implementation types in their signature: field
   * types, parameter, return and thrown types, including type arguments.
   */
  private static ArchCondition<JavaMember> notExposeInternalTypes() {
    return new ArchCondition<>("not expose types of " + INTERNAL + " in their signature") {
      @Override
      public void check(JavaMember member, ConditionEvents events) {
        Set<JavaModifier> modifiers = member.getModifiers();
        if (!modifiers.contains(JavaModifier.PUBLIC)
            && !modifiers.contains(JavaModifier.PROTECTED)) {
          return;
        }
        List<JavaType> types = new ArrayList<>();
        if (member instanceof JavaField field) {
          types.add(field.getType());
        } else if (member instanceof JavaCodeUnit codeUnit) {
          types.add(codeUnit.getReturnType());
          types.addAll(codeUnit.getParameterTypes());
          codeUnit.getThrowsClause().forEach(t -> types.add(t.getRawType()));
        }
        Set<JavaClass> exposed = new LinkedHashSet<>();
        for (JavaType type : types) {
          for (JavaClass raw : type.getAllInvolvedRawTypes()) {
            if (raw.getPackageName().startsWith("org.bikesched.internal")) {
              exposed.add(raw);
            }
          }
        }
        for (JavaClass type : exposed) {
          events.add(
              SimpleConditionEvent.violated(
                  member, member.getFullName() + " exposes " + type.getName()));
        }
      }
    };
  }
}
