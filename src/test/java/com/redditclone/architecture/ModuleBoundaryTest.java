package com.redditclone.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

@AnalyzeClasses(packages = "com.redditclone", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryTest {

    @ArchTest
    static final ArchRule modules_are_free_of_cycles =
            SlicesRuleDefinition.slices().matching("com.redditclone.(*)..").should().beFreeOfCycles();

    @ArchTest
    static final ArchRule auth_repositories_are_only_accessed_within_auth =
            repositoryAccessRule("..auth..");

    @ArchTest
    static final ArchRule community_repositories_are_only_accessed_within_community =
            repositoryAccessRule("..community..");

    @ArchTest
    static final ArchRule post_repositories_are_only_accessed_within_post =
            repositoryAccessRule("..post..");

    @ArchTest
    static final ArchRule comment_repositories_are_only_accessed_within_comment =
            repositoryAccessRule("..comment..");

    @ArchTest
    static final ArchRule vote_repositories_are_only_accessed_within_vote =
            repositoryAccessRule("..vote..");

    @ArchTest
    static final ArchRule moderation_repositories_are_only_accessed_within_moderation =
            repositoryAccessRule("..moderation..");

    @ArchTest
    static final ArchRule media_repositories_are_only_accessed_within_media =
            repositoryAccessRule("..media..");

    @ArchTest
    static final ArchRule notify_repositories_are_only_accessed_within_notify =
            repositoryAccessRule("..notify..");

    @ArchTest
    static final ArchRule chat_repositories_are_only_accessed_within_chat =
            repositoryAccessRule("..chat..");

    @ArchTest
    static final ArchRule engagement_repositories_are_only_accessed_within_engagement =
            repositoryAccessRule("..engagement..");

    @ArchTest
    static final ArchRule follow_repositories_are_only_accessed_within_follow =
            repositoryAccessRule("..follow..");

    // Modules only talk to each other through services, never by reaching into another module's
    // repository directly (see the source plan's System architecture section). `common` and
    // SecurityConfig are exempt: wiring auth.JwtAuthFilter into the security filter chain is legitimate
    // composition-root config, not a cross-module reach-through.
    private static ArchRule repositoryAccessRule(String modulePackage) {
        return classes()
                .that().resideInAPackage(modulePackage)
                .and().haveSimpleNameEndingWith("Repository")
                .should().onlyBeAccessed().byClassesThat()
                .resideInAnyPackage(modulePackage, "..common..");
    }
}
