# Besu Plugin Development


## Current status

Currently Besu plugins are mainly distributed as uber jars, where all the dependencies, including Besu’s one, are merged into a single final jar.\
We need to keep in mind that plugins are not meant to be run standalone, but they run in the Besu context, where all the Besu dependencies are already loaded.


## Uber Jar Pain Points

### Size

A plugin always depends on some Besu libs, plus it can use some 3rd party dependencies that are already provided by Besu, by default the uber jar packs everything, duplicating many classes, and there are cases where it basically has the same size of Besu itself, while its code base is very small.\
It is possible to tune the uber jar creation, manually removing packages that we know are duplicates, but this is not future proof and needs to be manually reviewed and implemented in every plugin independently


### File override / Information Lost

This happens when different jars contain the same file with a different content, since we are merging everything, only one of these files will be present in the final uber jar.\
Notoriously this creates the issue with META-INF/MANIFEST.MF, that is used to expose the version of the Tracer loaded by Besu at runtime (see <https://github.com/Consensys/linea-besu-package/issues/208>).\
By default plugins that include the Tracer as dependency will overwrite the MANIFEST.MF with their own data, breaking the version reported.\
There are workarounds, like the one used in the Sequencer, to force setting the MANIFEST.MF with the Tracer version, but they are of course ugly. Another possibility is to exclude the Tracer from the uber jar, but this requires that Tracer jar is included by some other means.


### Dependency conflict

This can happen between Besu and a plugin or between two or more plugins, and happens when different versions of the same dependency are referenced by Besu and the plugins, and the issue could only be spotted at runtime with a specific mix of Besu and plugins.\
A way to mitigate this risk is to have plugins to use the Besu BOM to enforce the version of the dependencies, like it is done for the Tracer and the Sequencer, but that works for single plugin deployment, when multiple plugins are involved they could bring in different versions of an extra dependency that is not covered by the BOM.

Also the BOM evolves with Besu versions, so if a plugin is built against a previous version of Besu, it is possible that it brings previous versions of the 3rd party library as well.

To solve this problem, different things are needed, at build time and at runtime.

At build time we need to get rid of the uber jar, and create a distribution of the plugin that only contains the plugin jar, plus 3rd party jars that are not already provided by Besu, this addresses the single plugin deployment scenario.

At runtime Besu should be able to identify what different plugins are using as dependencies and warn or fail in case it detects conflicts, this covers the multi plugins deployment, and plugin built against a different version of Besu (<https://github.com/hyperledger/besu/issues/8551>)


## Proposed solutions

Since there are different issues to solve, they could be addressed incrementally so that the DevUX and quality of plugins can be improved quickly.

Firstly we need to work on the distribution format of the plugin, preliminary work on this has been done for the Tracer and the Sequencer, using custom Gradle configuration, to create an archive that only contains the plugin jar, plus any 3rd party jar that is not already provided by Besu, this approach removes the need for the uber jar, and reduce the size of the distribution to only what is really needed for the plugin to work.

The custom Gradle configuration is clumsy and worked as a PoC of what could be done, it is not a complete solution, because to be applied to other plugins it needs to be copy\&pasted, and so changes need to be applied manually on every plugin every time, it is not future proof and it is limited to one aspect of the plugin development. The natural evolution is to create a Gradle plugin that incorporates that idea and extend it to other aspects of the plugin development.


### Besu Plugin DevUX

My ideal view of Besu Plugin DevUX, is that the developer should only focus on the plugin development and not think about the plumbing too much, avoid writing boiler plate code and should be driven and possibly warned when things could go wrong.

We also need to remember that plugins are meant to work in the Besu context, they are not standalone applications, so there are constraints and rules they must adhere to.

Ideal workflow:

- Dev should only declare which version of Besu he want to use for the plugin, then automatically all the dependencies are available without the need to manually declare them

* Dev only needs to declare extra dependencies that are not already provided by Besu (the build process should be smart enough to detect and warn the dev in case (s)he makes an error and creates a conflict)

* When it is time to distribute the plugin, the build process takes care of everything, w/o the need to manually customize the output artifacts)


### Gradle Plugin for Besu Plugins

Most of these features are implemented, and under test, with a Gradle plugin for Besu plugins.

The plugin takes care of:

- Setting all the Maven repositories needed to fetch the Besu dependencies

- Prepopulate the compile classpath with all the dependencies provided by Besu, using the BOM and an artifacts catalog (more on this later)

- Create a distribution of the plugin that only contains the plugin code plus any jar that is not already provided by Besu

What is still missing are the checks to notify the dev about possible errors (s)he did declaring extra dependencies.


Temp maven repo before publishing to Gradle Plugin portal: <https://github.com/Consensys/besu-plugin-gradle-plugin-artifacts>
PR to use it in the Sequencer: <https://github.com/Consensys/linea-monorepo/pull/1282>




### Besu artifacts catalog

With the Gradle plugin we address the DevUX, but there are other issues to solve, that are related to the runtime phase, when multiple plugins can conflict between themselves and with different versions of Besu.

Since at runtime we do not have all the information related to the dependencies, that were available during the build time, a solution is to add them to the distribution of Besu and plugins.

Basically the Besu artifacts catalog contains the information about the Maven coordinates of each jar that is present in the Besu distribution (jars in the lib folder), the same for the plugins, and the catalog is included in the distribution so it can be used at runtime (it is embedded in the main jar of Besu and plugins)

The catalog has a double use, during the build process to complement information not present in the BOM, and at runtime, when it can be read during the startup phase of Besu, when it is time to load plugins, when all the catalogs can be fetched and processed to identify possible conflicts, and according to some configuration options, warn the user or fail the startup.

Besu catalog <https://github.com/hyperledger/besu/pull/8987>
