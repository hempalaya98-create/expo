// Fuses every autolinked Android module + the brownfield library into a single
// publishable AAR via AGP's `com.android.fused-library` (Preview, AGP 8.13+).
//
// One sibling subproject per variant — `:<libraryName>-fused-release` and
// `-fused-debug` — both rendered from this template; `{{fusedVariant}}` is the
// only delta.

plugins {
  id("com.android.fused-library")
  id("maven-publish")
}

group = "${{groupId}}"

version = "${{version}}"

val fusedVariant = "${{fusedVariant}}"
val isReleaseVariant = fusedVariant == "release"
val fusedVariantCapitalized = fusedVariant.replaceFirstChar { it.uppercase() }

androidFusedLibrary {
  namespace = "${{packageId}}.fused.${{fusedVariant}}"
  minSdk = 24
  aarMetadata { minCompileSdk = 36 }
}

// Dev tooling is `debugImplementation`-only on the brownfield library, so the
// release fat AAR must skip it. `setupFusedModeStripping` strips matching entries
// from the generated `ExpoModulesPackageList.kt` to avoid `NoClassDefFoundError`
// at host startup. Extend ad-hoc via `-Pbrownfield.fused.skip=foo,bar`.
val devOnlySkipProjects: Set<String> = if (isReleaseVariant) {
  setOf("expo-dev-client", "expo-dev-launcher", "expo-dev-menu", "expo-dev-menu-interface")
} else {
  emptySet()
}
val extraSkip: Set<String> = (project.findProperty("brownfield.fused.skip") as? String)
  ?.split(',')
  ?.map { it.trim() }
  ?.filter { it.isNotEmpty() }
  ?.toSet()
  ?: emptySet()
val fusedSkipProjects = devOnlySkipProjects + extraSkip

// Force sibling evaluation before resolving include() targets and walking their
// runtime classpaths — without this, plugin detection and classpath resolution
// see incomplete state. Skip self and the OTHER fused sibling to avoid a cycle.
rootProject.subprojects.forEach {
  if (it.path == project.path) return@forEach
  if (it.name == "${{libraryName}}-fused-release") return@forEach
  if (it.name == "${{libraryName}}-fused-debug") return@forEach
  evaluationDependsOn(it.path)
}

// Foundational libraries the host app already provides — bundling them in the
// fused AAR causes duplicate-class errors at dex merge. Stay external in the POM.
// Extend via `-Pbrownfield.fused.exclude-transitive=foo,bar`.
val transitiveIncludeDenylistNonAndroidX = setOf(
  "org.jetbrains.kotlin",
  "org.jetbrains.kotlinx",
  "com.facebook.fbjni",
  "com.facebook.fresco",
  "com.facebook.hermes",
  "com.facebook.react",
  "com.facebook.soloader",
  "com.facebook.yoga",
  "com.google.android.material",
  "com.google.guava",
  "com.squareup.okhttp3",
  "com.squareup.okio",
  // KMP libs whose multiplatform parent coord is `pom`-only — our `artifactType="aar"`
  // filter can't include the parent, but AGP fused-library demands it as the `-android`
  // child's "parent dependency." Keep external; the host resolves via JitPack.
  "io.github.lukmccall",
)
// AndroidX subgroups we DO want fused (ExoPlayer for expo-video, CameraX for
// expo-camera). All other `androidx.*` stays external — Fused Library rejects
// partial transitive chains, easy to trip with cross-depending AndroidX modules.
val androidxFuseAllowlist = setOf(
  "androidx.camera",
  "androidx.media3",
)
val extraDenylist: Set<String> =
  (project.findProperty("brownfield.fused.exclude-transitive") as? String)
    ?.split(',')
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() }
    ?.toSet()
    ?: emptySet()
val effectiveDenylist = transitiveIncludeDenylistNonAndroidX + extraDenylist
// True → coord stays external in the POM. AndroidX uses an allowlist, everything
// else uses the denylist above.
fun isGroupDenied(group: String): Boolean {
  if (effectiveDenylist.any { group == it || group.startsWith("$it.") }) return true
  if (group == "androidx" || group.startsWith("androidx.")) {
    return androidxFuseAllowlist.none { group == it || group.startsWith("$it.") }
  }
  return false
}

// Override AGP fused-library's internal `fusedRuntime` configuration in two ways:
//  1. Flip its hardcoded `BuildTypeAttr=release` to match the sibling's `fusedVariant`,
//     so the `-fused-debug` sibling actually fuses debug-compiled bytecode (correct
//     `BuildConfig.DEBUG=true`, dev-only code paths active).
//  2. Apply the KMP / per-build-type excludes here ONLY (not in `configureEach` below)
//     so AGP fused-library doesn't try to fuse these coords (which trip "parent
//     dependency not included" validation or leak both variants at dex merge), while
//     the consumer-facing POM still lists them as transitive deps — the consumer
//     resolves them normally via Maven/JitPack.
configurations.all {
  if (name.startsWith("fusedRuntime")) {
    attributes {
      attribute(
        com.android.build.api.attributes.BuildTypeAttr.ATTRIBUTE,
        project.objects.named(com.android.build.api.attributes.BuildTypeAttr::class.java, fusedVariant)
      )
    }
  }
}

// AGP Fused Library rejects any `androidx.databinding:*` dep (including
// `viewbinding`, pulled transitively by `react { autolinkLibrariesWithApp() }`).
// Strip them from every configuration. MUST be registered BEFORE the aggregator
// resolves, otherwise `configureEach` throws "Cannot mutate after resolved".
configurations.configureEach {
  exclude(group = "androidx.databinding", module = "viewbinding")
  exclude(group = "androidx.databinding", module = "databinding-common")
  exclude(group = "androidx.databinding", module = "databinding-runtime")
  exclude(group = "androidx.databinding", module = "databinding-adapters")
  exclude(group = "androidx.databinding", module = "databinding-ktx")

  fusedSkipProjects.forEach { skipName -> exclude(module = skipName) }
}

// Aggregator configuration: depends on every autolinked module, resolved locally
// to avoid Gradle 8+'s "attempted without an exclusive lock" on cross-project
// resolution. `BuildTypeAttr` picks the matching variant per module.
val expoAggregator = configurations.create("brownfieldFusedExpoAggregator") {
  isCanBeResolved = true
  isCanBeConsumed = false
  attributes {
    attribute(
      org.gradle.api.attributes.Usage.USAGE_ATTRIBUTE,
      project.objects.named(org.gradle.api.attributes.Usage::class.java, org.gradle.api.attributes.Usage.JAVA_RUNTIME)
    )
    attribute(
      org.gradle.api.attributes.Category.CATEGORY_ATTRIBUTE,
      project.objects.named(org.gradle.api.attributes.Category::class.java, org.gradle.api.attributes.Category.LIBRARY)
    )
    attribute(
      org.gradle.api.attributes.LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
      project.objects.named(org.gradle.api.attributes.LibraryElements::class.java, "aar")
    )
    attribute(
      com.android.build.api.attributes.BuildTypeAttr.ATTRIBUTE,
      project.objects.named(com.android.build.api.attributes.BuildTypeAttr::class.java, fusedVariant)
    )
  }
  // Conflicting `-android`-suffixed Guava versions confuse Gradle's SemVer
  // comparator and cascade into lenient resolution returning zero artifacts.
  // Guava is denylisted anyway (host provides it), so just drop it here.
  exclude(group = "com.google.guava", module = "guava")
}

// `project.path` inside a `Project` extension function resolves to the RECEIVER
// (Project.getProject() returns `this`), not the script's project. Capture it in
// an outer `val` so the self-check compares against THIS fused module.
val thisProjectPath: String = project.path

// `plugins.hasPlugin("com.android.library")` returns false on Expo modules because
// `expo-module-gradle-plugin` applies AGP via `pluginManager.apply(LibraryPlugin::class.java)`,
// which doesn't register the plugin ID. Match by class FQN to catch both apply paths.
fun Project.hasAndroidLibraryPlugin(): Boolean {
  if (plugins.hasPlugin("com.android.library")) return true
  if (plugins.toList().any { p ->
        val n = p.javaClass.name
        (n.startsWith("com.android.build.gradle.") || n.startsWith("com.android.build.api.")) &&
          n.endsWith("LibraryPlugin")
      }) return true
  val android = extensions.findByName("android") ?: return false
  return android.javaClass.name.contains("Library", ignoreCase = true)
}

fun Project.isFusableAndroidLibrary(): Boolean {
  if (path == thisProjectPath) return false
  if (name == "${{libraryName}}") return false
  if (name == "${{libraryName}}-fused-release") return false
  if (name == "${{libraryName}}-fused-debug") return false
  if (name in fusedSkipProjects) return false
  if (!hasAndroidLibraryPlugin()) return false
  return true
}

val fusableSubprojects: List<Project> = rootProject.subprojects.filter { it.isFusableAndroidLibrary() }
logger.lifecycle(
  "brownfield.fused[${fusedVariant}]: fusing ${fusableSubprojects.size} subprojects: " +
    fusableSubprojects.joinToString(", ") { it.name }
)

dependencies {
  fusableSubprojects.forEach { sub ->
    add("brownfieldFusedExpoAggregator", sub)
  }
}

// Coords whose classes we explicitly DON'T fuse (excluded from `fusedRuntime` above
// for validation / dex-merge reasons) but which the consumer still needs to resolve
// at runtime. We piggy-back on the aggregator walk to discover the actual versions
// (no hardcoding), then inject them as POM/module-metadata transitive deps in the
// publish step below.
//
// EXACT group match only (no subgroup matching). The natural POM emission has separate
// entries for sub-namespaced groups should be left alone.
val externalDepsForConsumer = mutableListOf<Triple<String, String, String>>()
val groupsExcludedFromFuseButNeededAtRuntime = setOf("com.composables")

// Walk the aggregator's resolved artifacts to collect external AAR coords for
// `include()`. Auto-discovers ExoPlayer, CameraX, Glide, etc. Lenient via the
// modern `artifactView` API — the legacy `lenientConfiguration` throws before
// you can read the lenient surface, masking the real failure.
val transitiveAarIncludes: Set<String> = run {
  val collected = mutableSetOf<String>()
  val artifactView = expoAggregator.incoming.artifactView {
    isLenient = true
    attributes {
      attribute(
        org.gradle.api.attributes.Attribute.of("artifactType", String::class.java),
        "aar"
      )
    }
  }
  artifactView.artifacts.artifacts.forEach { result ->
    val id = result.id.componentIdentifier
    if (id !is org.gradle.api.artifacts.component.ModuleComponentIdentifier) return@forEach
    if (result.file.extension != "aar") return@forEach
    val group = id.group
    // Record coords whose group is in `groupsExcludedFromFuseButNeededAtRuntime`
    // BEFORE any filter rejects them — the per-build-type suffix filter, the
    // denylist, and the fusedRuntime exclude all silently drop these from the
    // fused output, so they'd otherwise vanish entirely. The consumer still
    // needs them at runtime; capture the resolved version here for injection
    // into the published POM / .module below.
    if (group in groupsExcludedFromFuseButNeededAtRuntime) {
      externalDepsForConsumer.add(Triple(group, id.module, id.version))
    }
    if (isGroupDenied(group)) return@forEach
    if (rootProject.findProject(":${id.module}") != null) return@forEach
    // Drop per-build-type coords (e.g. `com.composables:core-android-debug`):
    // AGP fused-library's internal release-hardcoded resolution trips on
    // cross-variant lookups. Leave them external; the host resolves them.
    if (id.module.endsWith("-debug") || id.module.endsWith("-release")) return@forEach
    collected += "${group}:${id.module}:${id.version}"
  }
  logger.lifecycle(
    "brownfield.fused[${fusedVariant}]: collected ${collected.size} external AAR coords"
  )
  if (externalDepsForConsumer.isNotEmpty()) {
    logger.lifecycle(
      "brownfield.fused[${fusedVariant}]: will declare ${externalDepsForConsumer.size} non-fused " +
        "transitives for consumer to resolve: ${externalDepsForConsumer.joinToString(", ") { "${it.first}:${it.second}:${it.third}" }}"
    )
  }
  collected
}

dependencies {
  // User's BrownfieldActivity / Fragment / Host code.
  include(project(":${{libraryName}}"))

  // Every autolinked Android module (Expo + RN community).
  rootProject.subprojects.forEach { sub ->
    if (!sub.isFusableAndroidLibrary()) return@forEach
    include(project(sub.path))
  }

  // External heavy libs (ExoPlayer, CameraX, Glide, ...). Including them merges
  // R.txt + resources so `rewriteClasses` can resolve the FQNs modules reference.
  transitiveAarIncludes.forEach { coord -> include(coord) }
}

publishing {
  // Mirror the root project's `expoBrownfieldPublishPlugin` repositories. The
  // publish plugin skips fused modules (no `LibraryExtension`), so without this
  // loop no `publishBrownfield<V>PublicationTo<X>Repository` tasks are created.
  repositories {
    val rootPublishConfig =
      rootProject.extensions.findByType(expo.modules.plugin.ExpoPublishExtension::class.java)
    rootPublishConfig?.publications?.forEach { pubConfig ->
      when (pubConfig.type.get()) {
        "localMaven" -> mavenLocal()
        "localDirectory", "remotePublic" -> maven {
          name = pubConfig.name
          url = uri(pubConfig.url.get())
          isAllowInsecureProtocol = pubConfig.allowInsecure.get()
        }
        "remotePrivate" -> maven {
          name = pubConfig.name
          url = uri(pubConfig.url.get())
          credentials {
            username = pubConfig.username.get()
            password = pubConfig.password.get()
          }
          isAllowInsecureProtocol = pubConfig.allowInsecure.get()
        }
      }
    }
  }

  publications {
    register<MavenPublication>("brownfield${fusedVariantCapitalized}") {
      afterEvaluate { from(components["fusedLibraryComponent"]) }
      // Strip POM entries we don't want consumers resolving:
      //  1. `fusedSkipProjects` modules — fused-library uses Gradle project names
      //     (`expo-camera`) instead of real coords (`expo.modules.camera`), so they
      //     can't be resolved by consumers anyway.
      //  2. Any `com.composables` / `io.github.lukmccall` entries the natural POM
      //     emission added — these include the KMP umbrella AND the variant-baked-
      //     into-name release coord (e.g. `core-android` with no suffix, which
      //     contains release-variant classes). The consumer pulling both that and
      //     our injected `-debug` coord hits a duplicate-class dex-merge error.
      //     Stripping them all here lets the `doLast` below add just the right
      //     variant-suffixed entries from the aggregator's resolved versions.
      pom.withXml {
        val deps = (asNode().get("dependencies") as? groovy.util.NodeList)?.firstOrNull() as? groovy.util.Node
          ?: return@withXml
        val toRemove = deps.children().filterIsInstance<groovy.util.Node>().filter { dep ->
          val artifactId = (dep.get("artifactId") as? groovy.util.NodeList)?.firstOrNull()
            ?.let { (it as? groovy.util.Node)?.text() }
          val groupId = (dep.get("groupId") as? groovy.util.NodeList)?.firstOrNull()
            ?.let { (it as? groovy.util.Node)?.text() } ?: ""
          artifactId in fusedSkipProjects || groupId in groupsExcludedFromFuseButNeededAtRuntime
        }
        toRemove.forEach { deps.remove(it) }
      }
    }
  }
}

// Tell consumers' variant matchers that this AAR was compiled against the RELEASE
// variants of `react-android` / `hermes-android` — required regardless of which
// fused sibling (debug or release) we're publishing.
//
// AGP's `com.android.fused-library` Preview hardcodes `release` for its internal
// `fusedRuntime` resolution, so every codegen `.so` and bytecode inside the AAR is
// linked against release-variant RN headers. Without this hint, a consumer's debug
// build would resolve the debug variants of those Maven coords and crash with
// SIGSEGV at component-descriptor init (`HostPlatformViewProps` ctor — vtable
// layout differs between RN debug and release builds).
//
// `pom.withXml` won't fix it: POM has no variant-attribute representation. The fix
// lives in the Gradle Module Metadata (`.module` JSON), which is what Gradle
// actually consults when resolving transitives from a Maven coord.
// AGP `com.android.fused-library` auto-registers a `maven` publication using the
// same component and coords as our explicit `brownfield<V>` publication. The
// aggregate `publishToMavenLocal` task publishes both at the same coord — and the
// `maven` one overwrites our annotated POM/.module. Disable every task that
// publishes the auto-registered `maven` publication so only our publication wins.
tasks.matching { it.name.startsWith("publishMavenPublicationTo") }.configureEach {
  enabled = false
}

val metadataTaskName = "generateMetadataFileForBrownfield${fusedVariantCapitalized}Publication"
afterEvaluate {
  tasks.named(metadataTaskName).configure {
    doLast {
      val moduleFile = outputs.files.singleFile
      if (!moduleFile.exists()) return@doLast
      // Full JSON round-trip — properly handles nested `version`/`excludes` objects
      // that regex-based depth tracking would mangle. We need to BOTH strip the
      // natural emission's variant-conflicting entries (e.g. `com.composables:core-android`
      // suffix-less, which contains release-variant classes) AND inject the
      // suffix-specific debug entries from the aggregator's resolution. The two
      // transformations must run on the same JSON tree.
      @Suppress("UNCHECKED_CAST")
      val root = groovy.json.JsonSlurper().parseText(moduleFile.readText()) as MutableMap<String, Any?>
      val variants = root["variants"] as? MutableList<MutableMap<String, Any?>> ?: return@doLast
      var annotatedCount = 0
      var strippedCount = 0
      val perVariantAbiCoords = setOf(
        "com.facebook.react" to "react-android",
        "com.facebook.hermes" to "hermes-android",
      )
      variants.forEach { variant ->
        val deps = variant["dependencies"] as? MutableList<MutableMap<String, Any?>> ?: return@forEach
        // Strip entries whose group is in `groupsExcludedFromFuseButNeededAtRuntime`
        // — we'll re-inject just the variant-specific coord at the end. Keeps consumer
        // resolution from pulling both the suffix-less (release) and `-debug` coord
        // and dex-merging duplicate classes.
        val before = deps.size
        deps.removeAll { dep ->
          val g = dep["group"] as? String ?: return@removeAll false
          g in groupsExcludedFromFuseButNeededAtRuntime
        }
        strippedCount += before - deps.size
        // Annotate react-android / hermes-android edges with BuildTypeAttr=release
        // so consumer variant matching pulls the variant the brownfield was compiled
        // against (AGP fused-library is internally hardcoded to release; without
        // this hint, consumer debug builds get the debug variant and SIGSEGV).
        deps.forEach { dep ->
          val key = (dep["group"] as? String) to (dep["module"] as? String)
          if (key in perVariantAbiCoords && dep["attributes"] == null) {
            dep["attributes"] = mutableMapOf<String, Any?>(
              "com.android.build.api.attributes.BuildTypeAttr" to "release"
            )
            annotatedCount++
          }
        }
      }
      // Inject the suffix-specific coords for non-fused-but-needed-at-runtime libs
      // into the runtimePublication variant. The aggregator walk recorded the actual
      // resolved versions for this build's `fusedVariant`, so we always inject the
      // right ones (debug suffix for debug, release/none for release).
      if (externalDepsForConsumer.isNotEmpty()) {
        val runtimeVariant = variants.firstOrNull { (it["name"] as? String) == "runtimePublication" }
        if (runtimeVariant != null) {
          @Suppress("UNCHECKED_CAST")
          val deps = (runtimeVariant["dependencies"] as? MutableList<MutableMap<String, Any?>>)
            ?: mutableListOf<MutableMap<String, Any?>>().also { runtimeVariant["dependencies"] = it }
          externalDepsForConsumer.forEach { (group, module, version) ->
            deps.add(mutableMapOf(
              "group" to group,
              "module" to module,
              "version" to mutableMapOf("requires" to version),
            ))
          }
        }
      }
      moduleFile.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(root)))
      logger.lifecycle(
        "brownfield.fused[${fusedVariant}]: annotated $annotatedCount dependency edges " +
          "with BuildTypeAttr=release; stripped $strippedCount conflicting natural-emission " +
          "entries; injected ${externalDepsForConsumer.size} non-fused transitive(s) in " +
          "published module metadata"
      )
    }
  }
  // POM injection happens at the task-output level (not via `pom.withXml`) because
  // Gradle's `MavenPublication` runs a consistency check AFTER `withXml` that strips
  // dependency entries whose coords don't appear in the resolved configurations.
  // Our `fusedRuntime` exclude removes lukmccall/composables from the resolved
  // deps, so any `withXml` injection of those entries gets silently dropped before
  // the file is written. Hooking the generatePom task's `doLast` runs after that
  // consistency check, so the entries survive into the published .pom.
  val pomTaskName = "generatePomFileForBrownfield${fusedVariantCapitalized}Publication"
  tasks.named(pomTaskName).configure {
    doLast {
      if (externalDepsForConsumer.isEmpty()) return@doLast
      val pomFile = outputs.files.singleFile
      logger.lifecycle("brownfield.fused[${fusedVariant}]: pom doLast targeting ${pomFile.absolutePath} (exists=${pomFile.exists()})")
      if (!pomFile.exists()) return@doLast
      val depEntries = externalDepsForConsumer.joinToString("\n") { (group, module, version) ->
        """    <dependency>
      <groupId>${group}</groupId>
      <artifactId>${module}</artifactId>
      <version>${version}</version>
      <scope>runtime</scope>
    </dependency>"""
      }
      val xml = pomFile.readText()
      val hasClosingTag = xml.contains("</dependencies>")
      val updated = if (hasClosingTag) {
        xml.replace("</dependencies>", "${depEntries}\n  </dependencies>")
      } else {
        xml.replace("</project>", "  <dependencies>\n${depEntries}\n  </dependencies>\n</project>")
      }
      pomFile.writeText(updated)
      logger.lifecycle(
        "brownfield.fused[${fusedVariant}]: injected ${externalDepsForConsumer.size} " +
          "non-fused transitive(s) into POM at ${pomFile.absolutePath} " +
          "(hasClosingDeps=$hasClosingTag, before=${xml.length} after=${updated.length})"
      )
    }
  }
}
