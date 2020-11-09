/*
 * A Gradle plugin for the creation of Minecraft mods and MinecraftForge plugins.
 * Copyright (C) 2013 Minecraft Forge
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 * USA
 */
package net.minecraftforge.gradle.user;

import static net.minecraftforge.gradle.common.Constants.*;

import java.io.File;
import java.util.List;

import org.gradle.api.Action;
import org.gradle.api.DefaultTask;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.maven.Conf2ScopeMappingContainer;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.internal.plugins.DslObject;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.plugins.MavenPluginConvention;
import org.gradle.api.tasks.GroovySourceSet;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.compile.GroovyCompile;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.javadoc.Javadoc;
import org.gradle.plugins.ide.eclipse.model.EclipseModel;
import org.gradle.plugins.ide.idea.model.IdeaModel;

import com.google.common.base.Strings;

import groovy.lang.Closure;
import net.minecraftforge.gradle.common.BasePlugin;
import net.minecraftforge.gradle.common.Constants;
import net.minecraftforge.gradle.tasks.ApplyFernFlowerTask;
import net.minecraftforge.gradle.tasks.CreateStartTask;
import net.minecraftforge.gradle.tasks.DeobfuscateJar;
import net.minecraftforge.gradle.tasks.PostDecompileTask;
import net.minecraftforge.gradle.tasks.RemapSources;
import net.minecraftforge.gradle.user.ReobfTaskFactory.ReobfTaskWrapper;
import net.minecraftforge.gradle.util.GradleConfigurationException;
import net.minecraftforge.gradle.util.delayed.DelayedFile;

public abstract class UserBasePlugin<T extends UserBaseExtension> extends BasePlugin<T>
{
    private boolean madeDecompTasks = false; // to gaurd against stupid programmers
    private final Closure<Object> makeRunDir = new Closure<Object>(UserBasePlugin.class) {
        public Object call()
        {
            delayedFile(REPLACE_RUN_DIR).call().mkdirs();
            return null;
        }
    };

    @Override
    public final void applyPlugin()
    {
        // apply the plugins
        this.applyExternalPlugin("java");
        this.applyExternalPlugin("eclipse");
        this.applyExternalPlugin("idea");

        // life cycle tasks
        Task task = makeTask(TASK_SETUP_CI, DefaultTask.class);
        task.setDescription("Sets up the bare minimum to build a minecraft mod. Ideally for CI servers");
        task.setGroup("ForgeGradle");
        task.dependsOn(TASK_DD_PROVIDED, TASK_DD_COMPILE);

        task = makeTask(TASK_SETUP_DEV, DefaultTask.class);
        task.setDescription("CIWorkspace + natives and assets to run and test Minecraft");
        task.setGroup("ForgeGradle");
        task.dependsOn(TASK_DD_PROVIDED, TASK_DD_COMPILE);

        task = makeTask(TASK_SETUP_DECOMP, DefaultTask.class);
        task.setDescription("DevWorkspace + the deobfuscated Minecraft source linked as a source jar.");
        task.setGroup("ForgeGradle");
        task.dependsOn(TASK_DD_PROVIDED, TASK_DD_COMPILE);

        // create configs
        project.getConfigurations().maybeCreate(CONFIG_MC);
        project.getConfigurations().maybeCreate(CONFIG_START);

        project.getConfigurations().maybeCreate(CONFIG_DEOBF_COMPILE);
        project.getConfigurations().maybeCreate(CONFIG_DEOBF_PROVIDED);

        // create the reobf named container
        NamedDomainObjectContainer<IReobfuscator> reobf = project.container(IReobfuscator.class, new ReobfTaskFactory(this));
        project.getExtensions().add(EXT_REOBF, reobf);

        configureCompilation();

        // Quality of life stuff for the users
        createSourceCopyTasks();
        doDevTimeDeobf();
        makeRunTasks();

        // IDE stuff
        configureIDEs();

        applyUserPlugin();
    }

    @Override
    protected void afterEvaluate()
    {
        // to guard against stupid programmers
        if (!madeDecompTasks)
        {
            throw new RuntimeException("THE DECOMP TASKS HAVENT BEEN MADE!! STUPID FORGEGRADLE DEVELOPER!!!! :(");
        }

        // verify runDir is set
        if (Strings.isNullOrEmpty(getExtension().getRunDir()))
        {
            throw new GradleConfigurationException("RunDir is not set!");
        }

        super.afterEvaluate();

        // add replacements for run configs and gradle start
        T ext = getExtension();
        replacer.putReplacement(REPLACE_CLIENT_TWEAKER, getClientTweaker(ext));
        replacer.putReplacement(REPLACE_CLIENT_MAIN, getClientRunClass(ext));

        // map configurations (only if the maven or maven publish plugins exist)
        mapConfigurations();

        // configure source replacement.
        project.getTasks().withType(TaskSourceCopy.class, new Action<TaskSourceCopy>() {
            @Override
            public void execute(TaskSourceCopy t)
            {
                t.replace(getExtension().getReplacements());
                t.include(getExtension().getIncludes());
            }
        });

        // add task depends for reobf
        if (project.getPlugins().hasPlugin("maven"))
        {
            project.getTasks().getByName("uploadArchives").dependsOn(TASK_REOBF);
        }

        // add GradleStart dep
        {
            ConfigurableFileCollection col = project.files(getStartDir());
            col.builtBy(TASK_MAKE_START);
            project.getDependencies().add(CONFIG_START, col);
        }

        // run task stuff
        // Add the mod and stuff to the classpath of the exec tasks.
        final Jar jarTask = (Jar) project.getTasks().getByName("jar");

        JavaExec exec = (JavaExec) project.getTasks().getByName("runClient");
        exec.classpath(project.getConfigurations().getByName("runtime"));
        exec.classpath(project.getConfigurations().getByName(CONFIG_MC));
        exec.classpath(project.getConfigurations().getByName(CONFIG_MC_DEPS));
        exec.classpath(project.getConfigurations().getByName(CONFIG_START));
        exec.classpath(jarTask.getArchivePath());
        exec.dependsOn(jarTask);
        exec.jvmArgs(getClientJvmArgs(getExtension()));
        exec.args(getClientRunArgs(getExtension()));
        exec.getOutputs().upToDateWhen(execTask -> false);
    }

    protected abstract void applyUserPlugin();

    /**
     * Sets up the default settings for reobf tasks.
     *
     * @param reobf The task to setup
     */
    protected void setupReobf(ReobfTaskWrapper reobf)
    {
        TaskSingleReobf task = reobf.getTask();
        task.setExceptorCfg(delayedFile(EXC_SRG));
        task.setFieldCsv(delayedFile(CSV_FIELD));
        task.setMethodCsv(delayedFile(CSV_METHOD));

        reobf.setMappingType(ReobfMappingType.NOTCH);
        JavaPluginConvention java = (JavaPluginConvention) project.getConvention().getPlugins().get("java");
        reobf.setClasspath(java.getSourceSets().getByName("main").getCompileClasspath());
    }

    @SuppressWarnings("unchecked")
	protected void makeDecompTasks(final String globalPattern, final String localPattern, Object inputJar, String inputTask, Object mcpPatchSet)
    {
        madeDecompTasks = true; // to guard against stupid programmers

        final DeobfuscateJar deobfBin = makeTask(TASK_DEOBF_BIN, DeobfuscateJar.class);
        deobfBin.setSrg(delayedFile(SRG_NOTCH_TO_MCP));
        deobfBin.setExceptorJson(delayedFile(MCP_DATA_EXC_JSON));
        deobfBin.setExceptorCfg(delayedFile(EXC_MCP));
        deobfBin.setFieldCsv(delayedFile(CSV_FIELD));
        deobfBin.setMethodCsv(delayedFile(CSV_METHOD));
        deobfBin.setApplyMarkers(false);
        deobfBin.setInJar(inputJar);
        deobfBin.setOutJar(chooseDeobfOutput(globalPattern, localPattern, "Bin", ""));
        deobfBin.dependsOn(inputTask, TASK_GENERATE_SRGS, TASK_DD_COMPILE, TASK_DD_PROVIDED);

        final Object deobfDecompJar = chooseDeobfOutput(globalPattern, localPattern, "", "srgBin");
        final Object decompJar = chooseDeobfOutput(globalPattern, localPattern, "", "decomp");
        final Object postDecompJar = chooseDeobfOutput(globalPattern, localPattern, "", "decompFixed");
        final Object remapped = chooseDeobfOutput(globalPattern, localPattern, "Src", "sources");
        final Object recompiledJar = chooseDeobfOutput(globalPattern, localPattern, "Src", "");

        final DeobfuscateJar deobfDecomp = makeTask(TASK_DEOBF, DeobfuscateJar.class);
        deobfDecomp.setSrg(delayedFile(SRG_NOTCH_TO_SRG));
        deobfDecomp.setExceptorJson(delayedFile(MCP_DATA_EXC_JSON));
        deobfDecomp.setExceptorCfg(delayedFile(EXC_SRG));
        deobfDecomp.setApplyMarkers(true);
        deobfDecomp.setInJar(inputJar);
        deobfDecomp.setOutJar(deobfDecompJar);
        deobfDecomp.dependsOn(inputTask, TASK_GENERATE_SRGS, TASK_DD_COMPILE, TASK_DD_PROVIDED); // todo grab correct task to depend on

        final ApplyFernFlowerTask decompile = makeTask(TASK_DECOMPILE, ApplyFernFlowerTask.class);
        decompile.setInJar(deobfDecompJar);
        decompile.setOutJar(decompJar);
        decompile.setClasspath(project.getConfigurations().getByName(Constants.CONFIG_MC_DEPS));
        decompile.dependsOn(deobfDecomp);

        final PostDecompileTask postDecomp = makeTask(TASK_POST_DECOMP, PostDecompileTask.class);
        postDecomp.setInJar(decompJar);
        postDecomp.setOutJar(postDecompJar);
        postDecomp.setPatches(mcpPatchSet);
        postDecomp.setAstyleConfig(delayedFile(MCP_DATA_STYLE));
        postDecomp.dependsOn(decompile);

        final RemapSources remap = makeTask(TASK_REMAP, RemapSources.class);
        remap.setInJar(postDecompJar);
        remap.setOutJar(remapped);
        remap.setFieldsCsv(delayedFile(CSV_FIELD));
        remap.setMethodsCsv(delayedFile(CSV_METHOD));
        remap.setParamsCsv(delayedFile(CSV_PARAM));
        remap.dependsOn(postDecomp);

        final TaskRecompileMc recompile = makeTask(TASK_RECOMPILE, TaskRecompileMc.class);
        recompile.setInSources(remapped);
        recompile.setClasspath(CONFIG_MC_DEPS);
        recompile.setOutJar(recompiledJar);
        recompile.dependsOn(remap, TASK_DL_VERSION_JSON);

        // create GradleStart
        final CreateStartTask makeStart = makeTask(TASK_MAKE_START, CreateStartTask.class);
        makeStart.addResource(GRADLE_START_CLIENT + ".java");
        makeStart.addReplacement("@@ASSETINDEX@@", delayedString(REPLACE_ASSET_INDEX));
        makeStart.addReplacement("@@ASSETSDIR@@", delayedFile(REPLACE_CACHE_DIR + "/assets"));
        makeStart.addReplacement("@@NATIVESDIR@@", delayedFile(DIR_NATIVES));
        makeStart.addReplacement("@@TWEAKERCLIENT@@", delayedString(REPLACE_CLIENT_TWEAKER));
        makeStart.addReplacement("@@BOUNCERCLIENT@@", delayedString(REPLACE_CLIENT_MAIN));
        makeStart.dependsOn(TASK_DL_ASSET_INDEX, TASK_DL_ASSETS, TASK_EXTRACT_NATIVES);
        makeStart.addReplacement("@@MCVERSION@@", delayedString(REPLACE_MC_VERSION));
        makeStart.addReplacement("@@SRGDIR@@", delayedFile(DIR_MCP_MAPPINGS + "/srgs/"));
        makeStart.addReplacement("@@SRG_NOTCH_SRG@@", delayedFile(SRG_NOTCH_TO_SRG));
        makeStart.addReplacement("@@SRG_NOTCH_MCP@@", delayedFile(SRG_NOTCH_TO_MCP));
        makeStart.addReplacement("@@SRG_SRG_MCP@@", delayedFile(SRG_SRG_TO_MCP));
        makeStart.addReplacement("@@SRG_MCP_SRG@@", delayedFile(SRG_MCP_TO_SRG));
        makeStart.addReplacement("@@SRG_MCP_NOTCH@@", delayedFile(SRG_MCP_TO_NOTCH));
        makeStart.addReplacement("@@CSVDIR@@", delayedFile(DIR_MCP_MAPPINGS));
        makeStart.setStartOut(getStartDir());
        makeStart.addClasspathConfig(CONFIG_MC_DEPS);
        makeStart.mustRunAfter(deobfBin, recompile);

        // setup reobf...
        ((NamedDomainObjectContainer<IReobfuscator>) project.getExtensions().getByName(EXT_REOBF)).create("jar");

        // add setup dependencies
        project.getTasks().getByName(TASK_SETUP_CI).dependsOn(deobfBin);
        project.getTasks().getByName(TASK_SETUP_DEV).dependsOn(deobfBin, makeStart);
        project.getTasks().getByName(TASK_SETUP_DECOMP).dependsOn(recompile, makeStart);

        // configure MC compiling. This AfterEvaluate section should happen after the one made in
        // also configure the dummy task dependencies
        project.afterEvaluate(new Action<Project>() {
            @Override
            public void execute(Project project)
            {
                if (project.getState().getFailure() != null)
                    return;

                // the recompiled jar exists, or the decomp task is part of the build
                boolean isDecomp = project.file(recompiledJar).exists() || project.getGradle().getStartParameter().getTaskNames().contains(TASK_SETUP_DECOMP);

                // set task dependencies
                if (!isDecomp)
                {
                    project.getTasks().getByName("compileJava").dependsOn(TASK_DEOBF_BIN);
                    project.getTasks().getByName("compileApiJava").dependsOn(TASK_DEOBF_BIN);
                }

                afterDecomp(isDecomp, useLocalCache(getExtension()), CONFIG_MC);
            }
        });
    }

    /**
     * This method returns an object that resolved to the correct pattern based on the useLocalCache() method
     *
     * @param globalPattern The global pattern
     * @param localPattern  The local pattern
     * @param appendage     The appendage
     * @param classifier    The classifier
     *
     * @return useable deobfsucated output file
     */
    @SuppressWarnings("serial")
    protected final Object chooseDeobfOutput(final String globalPattern, final String localPattern, final String appendage, final String classifier)
    {
        return new Closure<DelayedFile>(UserBasePlugin.class) {
            public DelayedFile call()
            {
                String classAdd = Strings.isNullOrEmpty(classifier) ? "" : "-" + classifier;
                String str = useLocalCache(getExtension()) ? localPattern : globalPattern;
                return delayedFile(String.format(str, appendage) + classAdd + ".jar");
            }
        };
    }

    /**
     * A boolean used to cache the output of useLocalCache;
     */
    protected boolean useLocalCache = false;

    /**
     * This method is called sufficiently late. Either afterEvaluate or inside a task, thus it has the extension object.
     * This method is called to decide whether or not to use the project-local cache instead of the global cache.
     * The actual locations of each cache are specified elsewhere.
     * @param extension The extension object of this plugin
     * @return whether or not to use the local cache
     */
    protected boolean useLocalCache(T extension)
    {
        return useLocalCache;
    }

    /**
     * Creates the api SourceSet and configures the classpaths of all the SourceSets to have MC and the MC deps in them.
     * Also sets the target JDK to java 8
     */
    protected void configureCompilation()
    {
        // get convention
        JavaPluginConvention javaConv = (JavaPluginConvention) project.getConvention().getPlugins().get("java");

        SourceSet main = javaConv.getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        SourceSet api = javaConv.getSourceSets().create("api");

        api.setCompileClasspath(api.getCompileClasspath()
                .plus(project.getConfigurations().getByName(CONFIG_MC))
                .plus(project.getConfigurations().getByName(CONFIG_MC_DEPS)));
        main.setCompileClasspath(main.getCompileClasspath()
                .plus(api.getOutput())
                .plus(project.getConfigurations().getByName(CONFIG_MC))
                .plus(project.getConfigurations().getByName(CONFIG_MC_DEPS)));
        main.setRuntimeClasspath(main.getCompileClasspath()
                .plus(api.getOutput())
                .plus(project.getConfigurations().getByName(CONFIG_MC))
                .plus(project.getConfigurations().getByName(CONFIG_MC_DEPS))
                .plus(project.getConfigurations().getByName(CONFIG_START)));

        project.getConfigurations().getByName(api.getCompileConfigurationName()).extendsFrom(project.getConfigurations().getByName("compile"));
        project.getConfigurations().getByName("testCompile").extendsFrom(project.getConfigurations().getByName("apiCompile"));

        Javadoc javadoc = (Javadoc) project.getTasks().getByName("javadoc");
        javadoc.setClasspath(main.getOutput().plus(main.getCompileClasspath()));

        // set the compile target
        javaConv.setSourceCompatibility("1.8");
        javaConv.setTargetCompatibility("1.8");
    }

    /**
     * Creates and partially configures the source replacement tasks. The actual replacements must be configured afterEvaluate.
     */
    protected void createSourceCopyTasks()
    {
        JavaPluginConvention javaConv = (JavaPluginConvention) project.getConvention().getPlugins().get("java");

        Action<SourceSet> action = new Action<SourceSet>() {
            @Override
            public void execute(SourceSet set) {

                TaskSourceCopy task;

                String capName = set.getName().substring(0, 1).toUpperCase() + set.getName().substring(1);
                String taskPrefix = "source"+capName;
                File dirRoot = new File(project.getBuildDir(), "sources/"+set.getName());

                // java
                {
                    File dir = new File(dirRoot, "java");

                    task = makeTask(taskPrefix+"Java", TaskSourceCopy.class);
                    task.setSource(set.getJava());
                    task.setOutput(dir);

                    // must get replacements from extension afterEvaluate()

                    JavaCompile compile = (JavaCompile) project.getTasks().getByName(set.getCompileJavaTaskName());
                    compile.dependsOn(task);
                    compile.setSource(dir);
                }

                // groovy
                if (project.getPlugins().hasPlugin("groovy"))
                {
                    GroovySourceSet langSet = (GroovySourceSet) new DslObject(set).getConvention().getPlugins().get("groovy");
                    File dir = new File(dirRoot, "groovy");

                    task = makeTask(taskPrefix+"Groovy", TaskSourceCopy.class);
                    task.setSource(langSet.getGroovy());
                    task.setOutput(dir);

                    // must get replacements from extension afterEValuate()

                    GroovyCompile compile = (GroovyCompile) project.getTasks().getByName(set.getCompileTaskName("groovy"));
                    compile.dependsOn(task);
                    compile.setSource(dir);
                }
            }
        };

        // for existing sourceSets
        for (SourceSet set : javaConv.getSourceSets())
        {
            action.execute(set);
        }
        // for user-defined ones
        javaConv.getSourceSets().whenObjectAdded(action);
    }

    protected final void doDevTimeDeobf()
    {
        final Task compileDummy = getDummyDep("compile", delayedFile(DIR_DEOBF_DEPS + "/compileDummy.jar"), TASK_DD_COMPILE);
        final Task providedDummy = getDummyDep("compile", delayedFile(DIR_DEOBF_DEPS + "/providedDummy.jar"), TASK_DD_PROVIDED);

        setupDevTimeDeobf(compileDummy, providedDummy);
    }

    protected void setupDevTimeDeobf(final Task compileDummy, final Task providedDummy)
    {
        // die wih error if I find invalid types...
        project.afterEvaluate(new Action<Project>() {
            @Override
            public void execute(Project project)
            {
                if (project.getState().getFailure() != null)
                    return;

                // add maven repo
                addMavenRepo(project, "deobfDeps", delayedFile(DIR_DEOBF_DEPS).call().getAbsoluteFile().toURI().getPath());
            }
        });
    }

    protected void makeRunTasks()
    {
        JavaExec exec = makeTask("runClient", JavaExec.class);
        exec.getOutputs().dir(delayedFile(REPLACE_RUN_DIR));
        exec.setMain(GRADLE_START_CLIENT);
        exec.doFirst(new Action<Task>()
        {
            @Override
            public void execute(Task task)
            {
                ((JavaExec) task).workingDir(delayedFile(REPLACE_RUN_DIR));
            }
        });
        exec.setStandardOutput(System.out);
        exec.setErrorOutput(System.err);

        exec.setGroup("ForgeGradle");
        exec.setDescription("Runs the Minecraft client");

        exec.doFirst(makeRunDir);

        exec.dependsOn("makeStart");
    }

    protected final TaskDepDummy getDummyDep(String config, DelayedFile dummy, String taskName)
    {
        TaskDepDummy dummyTask = makeTask(taskName, TaskDepDummy.class);
        dummyTask.setOutputFile(dummy);

        ConfigurableFileCollection col = project.files(dummy);
        col.builtBy(dummyTask);

        project.getDependencies().add(config, col);

        return dummyTask;
    }

    protected void mapConfigurations()
    {
        if (project.getPlugins().hasPlugin("maven"))
        {
            MavenPluginConvention mavenConv = (MavenPluginConvention) project.getConvention().getPlugins().get("maven");
            Conf2ScopeMappingContainer mappings = mavenConv.getConf2ScopeMappings();
            ConfigurationContainer configs = project.getConfigurations();
            final int priority = 500; // 500 is more than the compile config which is at 300

            mappings.setSkipUnmappedConfs(true); // dont want unmapped confs bieng compile deps..
            mappings.addMapping(priority, configs.getByName(CONFIG_DEOBF_COMPILE), Conf2ScopeMappingContainer.COMPILE);
            mappings.addMapping(priority, configs.getByName(CONFIG_DEOBF_PROVIDED), Conf2ScopeMappingContainer.PROVIDED);
        }
    }

    /**
     * This method should add the MC dependency to the supplied config, as well as do any extra configuration that requires the provided information.
     * @param isDecomp Whether to use the recmpield MC artifact
     * @param useLocalCache Whetehr or not ATs were applied to this artifact
     * @param mcConfig Which gradle configuration to add the MC dep to
     */
    protected abstract void afterDecomp(boolean isDecomp, boolean useLocalCache, String mcConfig);

    /**
     * The location where the GradleStart files will be generated to.
     * @return object that resolves to a file
     */
    protected abstract Object getStartDir();

    /**
     * To be inserted into GradleStart. Is called late afterEvaluate or at runtime.
     * @param ext the Extension object
     * @return empty string if no tweaker. NEVER NULL.
     */
    protected abstract String getClientTweaker(T ext);

    /**
     * To be inserted into GradleStart. Is called late afterEvaluate or at runtime.
     * @param ext the Extension object
     * @return empty string if default launchwrapper. NEVER NULL.
     */
    protected abstract String getClientRunClass(T ext);

    /**
     * For run configurations. Is called late afterEvaluate or at runtime.
     * @param ext the Extension object
     * @return empty list for no arguments. NEVER NULL.
     */
    protected abstract List<String> getClientRunArgs(T ext);

    /**
     * For run configurations. Is called late afterEvaluate or at runtime.
     * @param ext the Extension object
     * @return empty list for no arguments. NEVER NULL.
     */
    protected abstract List<String> getClientJvmArgs(T ext);

    protected void configureIDEs()
    {
        EclipseModel eclipseConv = (EclipseModel) project.getExtensions().getByName("eclipse");
        eclipseConv.getClasspath().getPlusConfigurations().add(project.getConfigurations().getByName(CONFIG_MC));
        eclipseConv.getClasspath().getPlusConfigurations().add(project.getConfigurations().getByName(CONFIG_MC_DEPS));
        eclipseConv.getClasspath().getPlusConfigurations().add(project.getConfigurations().getByName(CONFIG_START));

        IdeaModel ideaConv = (IdeaModel) project.getExtensions().getByName("idea");

        ideaConv.getModule().getExcludeDirs().addAll(project.files(".gradle", "build", ".idea", "out").getFiles());
        ideaConv.getModule().setDownloadJavadoc(true);
        ideaConv.getModule().setDownloadSources(true);

        ideaConv.getModule().getScopes().get("COMPILE").get("plus").add(project.getConfigurations().getByName(CONFIG_MC_DEPS));
        ideaConv.getModule().getScopes().get("COMPILE").get("plus").add(project.getConfigurations().getByName(CONFIG_MC));
        ideaConv.getModule().getScopes().get("RUNTIME").get("plus").add(project.getConfigurations().getByName(CONFIG_START));

        // add deobf task dependencies
        project.getTasks().getByName("ideaModule").dependsOn(TASK_DD_COMPILE, TASK_DD_PROVIDED).doFirst(makeRunDir);

        // fix the idea bug
        ideaConv.getModule().setInheritOutputDirs(true);
    }
}
