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
package net.minecraftforge.gradle.common;

import static net.minecraftforge.gradle.common.Constants.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

import org.gradle.api.Action;
import org.gradle.api.DefaultTask;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration.State;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.artifacts.repositories.FlatDirectoryArtifactRepository;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.logging.Logger;
import org.gradle.api.plugins.ExtraPropertiesExtension;
import org.gradle.api.tasks.Delete;

import com.google.common.base.Charsets;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import com.google.gson.reflect.TypeToken;

import groovy.lang.Closure;
import net.minecraftforge.gradle.tasks.Download;
import net.minecraftforge.gradle.tasks.DownloadAssetsTask;
import net.minecraftforge.gradle.tasks.EtagDownloadTask;
import net.minecraftforge.gradle.tasks.ExtractConfigTask;
import net.minecraftforge.gradle.tasks.GenSrgs;
import net.minecraftforge.gradle.util.FileLogListenner;
import net.minecraftforge.gradle.util.GradleConfigurationException;
import net.minecraftforge.gradle.util.delayed.DelayedFile;
import net.minecraftforge.gradle.util.delayed.DelayedFileTree;
import net.minecraftforge.gradle.util.delayed.DelayedString;
import net.minecraftforge.gradle.util.delayed.ReplacementProvider;
import net.minecraftforge.gradle.util.delayed.TokenReplacer;
import net.minecraftforge.gradle.util.json.JsonFactory;
import net.minecraftforge.gradle.util.json.version.Version;

public abstract class BasePlugin<K extends BaseExtension> implements Plugin<Project>
{
    public Project       project;
    public ReplacementProvider replacer = new ReplacementProvider();

    private Version                      mcVersionJson;

    @SuppressWarnings({ "rawtypes", "unchecked" })
    @Override
    public final void apply(Project arg)
    {
        project = arg;

        if (project.getBuildDir().getAbsolutePath().contains("!"))
        {
            project.getLogger().error("Build path has !, This will screw over a lot of java things as ! is used to denote archive paths, REMOVE IT if you want to continue");
            throw new RuntimeException("Build path contains !");
        }

        // set the obvious replacements
        replacer.putReplacement(REPLACE_CACHE_DIR, cacheFile("").getAbsolutePath());
        replacer.putReplacement(REPLACE_BUILD_DIR, project.getBuildDir().getAbsolutePath());

        // logging
        File projectCacheDir = project.getGradle().getStartParameter().getProjectCacheDir();
        if (projectCacheDir == null)
            projectCacheDir = new File(project.getProjectDir(), ".gradle");

        replacer.putReplacement(REPLACE_PROJECT_CACHE_DIR, projectCacheDir.getAbsolutePath());

        FileLogListenner listener = new FileLogListenner(new File(projectCacheDir, "gradle.log"));
        project.getLogging().addStandardOutputListener(listener);
        project.getLogging().addStandardErrorListener(listener);
        project.getGradle().addBuildListener(listener);

        // extension objects
        Type t = getClass().getGenericSuperclass();

        while (t instanceof Class)
        {
            t = ((Class) t).getGenericSuperclass();
        }

        project.getExtensions().create(EXT_NAME_MC, (Class<K>) ((ParameterizedType) t).getActualTypeArguments()[0], this);

        // add buildscript usable tasks
        ExtraPropertiesExtension ext = project.getExtensions().getExtraProperties();
        ext.set("Download", Download.class);
        ext.set("EtagDownload", EtagDownloadTask.class);

        // repos
        project.allprojects(proj -> {
            addMavenRepo(proj, "forge", URL_FORGE_MAVEN);
            proj.getRepositories().mavenCentral();
            addMavenRepo(proj, "minecraft", URL_LIBRARY);
        });

        // do Mcp Snapshots Stuff
        getRemoteJsons();
        project.getConfigurations().maybeCreate(CONFIG_MCP_DATA);
        project.getConfigurations().maybeCreate(CONFIG_MAPPINGS);

        // set other useful configs
        project.getConfigurations().maybeCreate(CONFIG_MC_DEPS);
        project.getConfigurations().maybeCreate(CONFIG_MC_DEPS_CLIENT);
        project.getConfigurations().maybeCreate(CONFIG_NATIVES);

        // should be assumed until specified otherwise
        project.getConfigurations().getByName(CONFIG_MC_DEPS).extendsFrom(project.getConfigurations().getByName(CONFIG_MC_DEPS_CLIENT));

        // after eval
        project.afterEvaluate(new Action<Project>() {
            @Override
            public void execute(Project project)
            {
                // dont continue if its already failed!
                if (project.getState().getFailure() != null)
                    return;

                afterEvaluate();
            }
        });

        // some default tasks
        makeCommonTasks();

        // at last, apply the child plugins
        applyPlugin();
    }

    public abstract void applyPlugin();

    private static boolean displayBanner = true;

    private void getRemoteJsons()
    {
        // MCP json
        File jsonCache = cacheFile("McpMappings.json");
        File etagFile = new File(jsonCache.getAbsolutePath() + ".etag");
        getExtension().mcpJson = JsonFactory.GSON.fromJson(getWithEtag(URL_MCP_JSON, jsonCache, etagFile), new TypeToken<Map<String, Map<String, int[]>>>() {}.getType());

        // MC manifest json
        jsonCache = cacheFile("McManifest.json");
        etagFile = new File(jsonCache.getAbsolutePath() + ".etag");
    }

    protected void afterEvaluate()
    {
        // validate MC version
        if (Strings.isNullOrEmpty(getExtension().getVersion()))
        {
            throw new GradleConfigurationException("You must set the Minecraft version!");
        }

        // http://files.minecraftforge.net/maven/de/oceanlabs/mcp/mcp/1.7.10/mcp-1.7.10-srg.zip
        project.getDependencies().add(CONFIG_MAPPINGS, ImmutableMap.of(
                "group", "de.oceanlabs.mcp",
                "name", delayedString("mcp_" + REPLACE_MCP_CHANNEL).call(),
                "version", delayedString(REPLACE_MCP_VERSION + "-" + REPLACE_MCP_MCVERSION).call(),
                "ext", "zip"
                ));

        project.getDependencies().add(CONFIG_MCP_DATA, ImmutableMap.of(
                "group", "de.oceanlabs.mcp",
                "name", "mcp",
                "version", delayedString(REPLACE_MC_VERSION).call(),
                "classifier", "srg",
                "ext", "zip"
                ));

        if (!displayBanner)
            return;

        Logger logger = this.project.getLogger();
        logger.lifecycle("#################################################");
        logger.lifecycle("               ForgeGradle 2.1                   ");
        logger.lifecycle(" https://github.com/hyperiumjailbreak/ForgeGradle");
        logger.lifecycle("#################################################");
        logger.lifecycle("               Powered by MCP unknown              ");
        logger.lifecycle("         by: Searge, ProfMobius, Fesh0r,         ");
        logger.lifecycle("         R4wk, ZeuX, IngisKahn, bspkrs           ");
        logger.lifecycle("#################################################");

        displayBanner = false;
    }

    @SuppressWarnings("serial")
    private void makeCommonTasks()
    {
        ExtractConfigTask extractNatives = makeTask(TASK_EXTRACT_NATIVES, ExtractConfigTask.class);
        extractNatives.setDestinationDir(delayedFile(DIR_NATIVES));
        extractNatives.setConfig(CONFIG_NATIVES);
        extractNatives.exclude("META-INF/**", "META-INF/**");
        extractNatives.setDoesCache(true);

        parseAndStoreVersion(project.file("src/main/resources/installer.target.json"), (File[]) null);

        EtagDownloadTask getAssetsIndex = makeTask(TASK_DL_ASSET_INDEX, EtagDownloadTask.class);
        getAssetsIndex.setUrl(new Closure<String>(BasePlugin.class) {
            @Override
            public String call()
            {
                return mcVersionJson.assetIndex.url;
            }
        });
        getAssetsIndex.setFile(delayedFile(JSON_ASSET_INDEX));
        getAssetsIndex.setDieWithError(false);

        DownloadAssetsTask getAssets = makeTask(TASK_DL_ASSETS, DownloadAssetsTask.class);
        getAssets.setAssetsDir(delayedFile(DIR_ASSETS));
        getAssets.setAssetsIndex(delayedFile(JSON_ASSET_INDEX));
        getAssets.dependsOn(getAssetsIndex);

        Download dlClient = makeTask(TASK_DL_CLIENT, Download.class);
        dlClient.setOutput(delayedFile(JAR_CLIENT_FRESH));
        dlClient.setUrl(new Closure<String>(BasePlugin.class) {
            @Override
            public String call()
            {
                return mcVersionJson.getClientUrl();
            }
        });

        ExtractConfigTask extractMcpData = makeTask(TASK_EXTRACT_MCP, ExtractConfigTask.class);
        {
            extractMcpData.setDestinationDir(delayedFile(DIR_MCP_DATA));
            extractMcpData.setConfig(CONFIG_MCP_DATA);
            extractMcpData.setDoesCache(true);
        }

        ExtractConfigTask extractMcpMappings = makeTask(TASK_EXTRACT_MAPPINGS, ExtractConfigTask.class);
        extractMcpMappings.setDestinationDir(delayedFile(DIR_MCP_MAPPINGS));
        extractMcpMappings.setConfig(CONFIG_MAPPINGS);
        extractMcpMappings.setDoesCache(true);

        GenSrgs genSrgs = makeTask(TASK_GENERATE_SRGS, GenSrgs.class);
        genSrgs.setInSrg(delayedFile(MCP_DATA_SRG));
        genSrgs.setInExc(delayedFile(MCP_DATA_EXC));
        genSrgs.setMethodsCsv(delayedFile(CSV_METHOD));
        genSrgs.setFieldsCsv(delayedFile(CSV_FIELD));
        genSrgs.setNotchToSrg(delayedFile(Constants.SRG_NOTCH_TO_SRG));
        genSrgs.setNotchToMcp(delayedFile(Constants.SRG_NOTCH_TO_MCP));
        genSrgs.setSrgToMcp(delayedFile(SRG_SRG_TO_MCP));
        genSrgs.setMcpToSrg(delayedFile(SRG_MCP_TO_SRG));
        genSrgs.setMcpToNotch(delayedFile(SRG_MCP_TO_NOTCH));
        genSrgs.setSrgExc(delayedFile(EXC_SRG));
        genSrgs.setMcpExc(delayedFile(EXC_MCP));
        genSrgs.setDoesCache(true);
        genSrgs.dependsOn(extractMcpData, extractMcpMappings);

        Delete clearCache = makeTask(TASK_CLEAN_CACHE, Delete.class);
        clearCache.delete(delayedFile(REPLACE_CACHE_DIR), delayedFile(DIR_LOCAL_CACHE));
        clearCache.setGroup(GROUP_FG);
        clearCache.setDescription("Cleares the ForgeGradle cache.");
    }

    /**
     * @return the extension object with name
     * @see Constants#EXT_NAME_MC
     */
    @SuppressWarnings("unchecked")
    public final K getExtension()
    {
        return (K) project.getExtensions().getByName(EXT_NAME_MC);
    }

    public DefaultTask makeTask(String name)
    {
        return makeTask(name, DefaultTask.class);
    }

    public DefaultTask maybeMakeTask(String name)
    {
        return maybeMakeTask(name, DefaultTask.class);
    }

    public <T extends Task> T makeTask(String name, Class<T> type)
    {
        return makeTask(project, name, type);
    }

    public <T extends Task> T maybeMakeTask(String name, Class<T> type)
    {
        return maybeMakeTask(project, name, type);
    }

    public static <T extends Task> T maybeMakeTask(Project proj, String name, Class<T> type)
    {
        return (T) proj.getTasks().maybeCreate(name, type);
    }

    public static <T extends Task> T makeTask(Project proj, String name, Class<T> type)
    {
        return (T) proj.getTasks().create(name, type);
    }

    public void applyExternalPlugin(String plugin)
    {
        project.apply(ImmutableMap.of("plugin", plugin));
    }

    public MavenArtifactRepository addMavenRepo(Project proj, final String name, final String url)
    {
        return proj.getRepositories().maven(new Action<MavenArtifactRepository>() {
            @Override
            public void execute(MavenArtifactRepository repo)
            {
                repo.setName(name);
                repo.setUrl(url);
            }
        });
    }

    public FlatDirectoryArtifactRepository addFlatRepo(Project proj, final String name, final Object... dirs)
    {
        return proj.getRepositories().flatDir(new Action<FlatDirectoryArtifactRepository>() {
            @Override
            public void execute(FlatDirectoryArtifactRepository repo)
            {
                repo.setName(name);
                repo.dirs(dirs);
            }
        });
    }

    protected String getWithEtag(String strUrl, File cache, File etagFile)
    {
        try
        {
            if (project.getGradle().getStartParameter().isOffline()) // dont even try the internet
                return Files.toString(cache, Charsets.UTF_8);

            // dude, its been less than 1 minute since the last time..
            if (cache.exists() && cache.lastModified() + 60000 >= System.currentTimeMillis())
                return Files.toString(cache, Charsets.UTF_8);

            String etag;
            if (etagFile.exists())
            {
                etag = Files.toString(etagFile, Charsets.UTF_8);
            }
            else
            {
                etagFile.getParentFile().mkdirs();
                etag = "";
            }

            URL url = new URL(strUrl);

            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setInstanceFollowRedirects(true);
            con.setRequestProperty("User-Agent", USER_AGENT);
            con.setIfModifiedSince(cache.lastModified());

            if (!Strings.isNullOrEmpty(etag))
            {
                con.setRequestProperty("If-None-Match", etag);
            }

            con.connect();

            String out = null;
            if (con.getResponseCode() == 304)
            {
                // the existing file is good
                Files.touch(cache); // touch it to update last-modified time, to wait another minute
                out = Files.toString(cache, Charsets.UTF_8);
            }
            else if (con.getResponseCode() == 200)
            {
                InputStream stream = con.getInputStream();
                byte[] data = ByteStreams.toByteArray(stream);
                Files.write(data, cache);
                stream.close();

                // write etag
                etag = con.getHeaderField("ETag");
                if (Strings.isNullOrEmpty(etag))
                {
                    Files.touch(etagFile);
                }
                else
                {
                    Files.write(etag, etagFile, Charsets.UTF_8);
                }

                out = new String(data);
            }
            else
            {
                project.getLogger().error("Etag download for " + strUrl + " failed with code " + con.getResponseCode());
            }

            con.disconnect();

            return out;
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        if (cache.exists())
        {
            try
            {
                return Files.toString(cache, Charsets.UTF_8);
            }
            catch (IOException e)
            {
                Throwables.propagate(e);
            }
        }

        throw new RuntimeException("Unable to obtain url (" + strUrl + ") with etag!");
    }

    /**
     * Parses the version json in the provided file, and saves it in memory.
     * Also populates the McDeps and natives configurations.
     * Also sets the ASSET_INDEX replacement string
     * Does nothing (returns null) if the file is not found, but hard-crashes if it could not be parsed.
     * @param file version file to parse
     * @param inheritanceDirs folders to look for the parent json, should include DIR_JSON
     * @return null if the file doesnt exist
     */
    protected Version parseAndStoreVersion(File file, File... inheritanceDirs)
    {
        if (!file.exists())
            return null;

        Version version = null;

        if (version == null)
        {
            try
            {
                version = JsonFactory.loadVersion(file, delayedString(REPLACE_MC_VERSION).call(), inheritanceDirs);
            }
            catch (Exception e)
            {
                project.getLogger().error("" + file + " could not be parsed");
                Throwables.propagate(e);
            }
        }

        // apply the dep info.
        DependencyHandler handler = project.getDependencies();

        // actual dependencies
        if (project.getConfigurations().getByName(CONFIG_MC_DEPS).getState() == State.UNRESOLVED)
        {
            for (net.minecraftforge.gradle.util.json.version.Library lib : version.getLibraries())
            {
                if (lib.natives == null)
                {
                    String configName = CONFIG_MC_DEPS;
                    if (lib.name.contains("java3d")
                            || lib.name.contains("paulscode")
                            || lib.name.contains("lwjgl")
                            || lib.name.contains("twitch")
                            || lib.name.contains("jinput"))
                    {
                        configName = CONFIG_MC_DEPS_CLIENT;
                    }

                    handler.add(configName, lib.getArtifactName());
                }
            }
        }

        // the natives
        if (project.getConfigurations().getByName(CONFIG_NATIVES).getState() == State.UNRESOLVED)
        {
            for (net.minecraftforge.gradle.util.json.version.Library lib : version.getLibraries())
            {
                if (lib.natives != null)
                    handler.add(CONFIG_NATIVES, lib.getArtifactName());
            }
        }

        // set asset index
        replacer.putReplacement(REPLACE_ASSET_INDEX, version.assetIndex.id);

        this.mcVersionJson = version;

        return version;
    }

    // DELAYED STUFF ONLY ------------------------------------------------------------------------
    private LoadingCache<String, TokenReplacer> replacerCache = CacheBuilder.newBuilder()
            .weakValues()
            .build(
                    new CacheLoader<String, TokenReplacer>() {
                        public TokenReplacer load(String key)
                        {
                            return new TokenReplacer(replacer, key);
                        }
                    });
    private LoadingCache<String, DelayedString> stringCache = CacheBuilder.newBuilder()
            .weakValues()
            .build(
                    new CacheLoader<String, DelayedString>() {
                        public DelayedString load(String key)
                        {
                            return new DelayedString(CacheLoader.class, replacerCache.getUnchecked(key));
                        }
                    });
    private LoadingCache<String, DelayedFile> fileCache = CacheBuilder.newBuilder()
            .weakValues()
            .build(
                    new CacheLoader<String, DelayedFile>() {
                        public DelayedFile load(String key)
                        {
                            return new DelayedFile(CacheLoader.class, project, replacerCache.getUnchecked(key));
                        }
                    });

    public DelayedString delayedString(String path)
    {
        return stringCache.getUnchecked(path);
    }

    public DelayedFile delayedFile(String path)
    {
        return fileCache.getUnchecked(path);
    }

    public DelayedFileTree delayedTree(String path)
    {
        return new DelayedFileTree(BasePlugin.class, project, replacerCache.getUnchecked(path));
    }

    protected File cacheFile(String path)
    {
        return new File(project.getGradle().getGradleUserHomeDir(), "caches/minecraft/" + path);
    }
}
