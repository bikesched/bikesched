package bikesched.core;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.members;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import bikesched.core.internal.Internals;
import bikesched.core.internal.Settings;
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
import org.junit.jupiter.api.Test;

/**
 * Rules of the package structure (see AGENTS.md): the API packages exported by the module reach the
 * implementation only through {@link Internals}, expose no implementation types, and the
 * implementation packages only depend downwards.
 */
class ArchitectureTest {

  /** The packages exported by {@code module-info.java}. */
  private static final String[] API = {
    "bikesched.core", "bikesched.core.job", "bikesched.core.event"
  };

  private static final String INTERNAL = "bikesched.core.internal..";

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("bikesched.core");

  @Test
  void apiReachesTheImplementationOnlyThroughInternals() {
    noClasses()
        .that()
        .resideInAnyPackage(API)
        .should()
        .dependOnClassesThat(
            resideInAPackage(INTERNAL).and(not(belongToAnyOf(Internals.class, Settings.class))))
        .because("the API creates the implementation via Internals only")
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
        .definedBy("bikesched.core.internal")
        .layer("Job")
        .definedBy("bikesched.core.internal.job..")
        .layer("Event")
        .definedBy("bikesched.core.internal.event..")
        .layer("Database")
        .definedBy("bikesched.core.internal.database")
        .layer("Schema")
        .definedBy("bikesched.core.internal.database.schema..")
        .whereLayer("Node")
        .mayNotBeAccessedByAnyLayer()
        .whereLayer("Job")
        .mayOnlyBeAccessedByLayers("Node")
        .whereLayer("Event")
        .mayOnlyBeAccessedByLayers("Node", "Job")
        .whereLayer("Database")
        .mayOnlyBeAccessedByLayers("Node", "Job", "Event")
        .whereLayer("Schema")
        .mayOnlyBeAccessedByLayers("Database")
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
            if (raw.getPackageName().startsWith("bikesched.core.internal")) {
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
