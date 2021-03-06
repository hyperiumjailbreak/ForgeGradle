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
package net.minecraftforge.gradle.tasks;

import com.google.common.base.Joiner;
import com.google.common.collect.Maps;
import com.google.common.io.Files;
import com.google.common.io.LineProcessor;
import de.oceanlabs.mcp.mcinjector.LVTNaming;
import de.oceanlabs.mcp.mcinjector.MCInjectorImpl;
import groovy.lang.Closure;
import net.md_5.specialsource.*;
import net.md_5.specialsource.provider.JarProvider;
import net.md_5.specialsource.provider.JointProvider;
import net.minecraftforge.gradle.common.Constants;
import net.minecraftforge.gradle.util.caching.Cached;
import net.minecraftforge.gradle.util.caching.CachedTask;
import net.minecraftforge.gradle.util.json.JsonFactory;
import net.minecraftforge.gradle.util.json.MCInjectorStruct;
import net.minecraftforge.gradle.util.json.MCInjectorStruct.InnerClass;
import org.gradle.api.tasks.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.zip.ZipFile;

public class DeobfuscateJar extends CachedTask
{
    @InputFile
    @Optional
    private Object            fieldCsv;
    @InputFile
    @Optional
    private Object            methodCsv;

    @InputFile
    private Object            inJar;

    @InputFile
    private Object            srg;

    @InputFile
    private Object            exceptorCfg;

    @InputFile
    private Object            exceptorJson;

    @Input
    private boolean           applyMarkers  = false;

    private Object            outJar;

    private Object            log;

    @TaskAction
    public void doTask() throws IOException
    {
        // make stuff into files.
        File tempObfJar = new File(getTemporaryDir(), "deobfed.jar"); // courtesy of gradle temp dir.
        File out = getOutJar();

        // deobf
        getLogger().lifecycle("Applying SpecialSource...");
        deobfJar(getInJar(), tempObfJar, getSrg());

        File log = getLog();
        if (log == null)
            log = new File(getTemporaryDir(), "exceptor.log");

        // apply exceptor
        getLogger().lifecycle("Applying Exceptor...");
        applyExceptor(tempObfJar, out, getExceptorCfg(), log);
    }

    private void deobfJar(File inJar, File outJar, File srg) throws IOException
    {
        // load mapping
        JarMapping mapping = new JarMapping();
        mapping.loadMappings(srg);

        // load in ATs
        ErroringRemappingAccessMap accessMap = new ErroringRemappingAccessMap(new File[] { getMethodCsv(), getFieldCsv() });

        // make a processor out of the ATS and mappings.
        RemapperProcessor srgProcessor = new RemapperProcessor(null, mapping, null);

        RemapperProcessor atProcessor = new RemapperProcessor(null, null, accessMap);
        // make remapper
        JarRemapper remapper = new JarRemapper(srgProcessor, mapping, atProcessor);

        // load jar
        Jar input = Jar.init(inJar);

        // ensure that inheritance provider is used
        JointProvider inheritanceProviders = new JointProvider();
        inheritanceProviders.add(new JarProvider(input));
        mapping.setFallbackInheritanceProvider(inheritanceProviders);

        // remap jar
        remapper.remapJar(input, outJar);
    }

    public void applyExceptor(File inJar, File outJar, File config, File log) throws IOException
    {
        String json = null;
        File getJson = getExceptorJson();
        if (getJson != null)
        {
            final Map<String, MCInjectorStruct> struct = JsonFactory.loadMCIJson(getJson);

            // Remove unknown classes from configuration
            removeUnknownClasses(inJar, struct);

            File jsonTmp = new File(this.getTemporaryDir(), "transformed.json");
            json = jsonTmp.getCanonicalPath();
            Files.write(JsonFactory.GSON.toJson(struct).getBytes(), jsonTmp);
        }

        MCInjectorImpl.process(inJar.getCanonicalPath(),
                outJar.getCanonicalPath(),
                config.getCanonicalPath(),
                log.getCanonicalPath(),
                null,
                0,
                json,
                isApplyMarkers(),
                true,
                LVTNaming.LVT
                );
    }

    private void removeUnknownClasses(File inJar, Map<String, MCInjectorStruct> config) throws IOException
    {
        ZipFile zip = new ZipFile(inJar);
        try
        {
            Iterator<Map.Entry<String, MCInjectorStruct>> entries = config.entrySet().iterator();
            while (entries.hasNext())
            {
                Map.Entry<String, MCInjectorStruct> entry = entries.next();
                String className = entry.getKey();

                // Verify the configuration contains only classes we actually have
                if (zip.getEntry(className + ".class") == null)
                {
                    getLogger().info("Removing unknown class {}", className);
                    entries.remove();
                    continue;
                }

                MCInjectorStruct struct = entry.getValue();

                // Verify the inner classes in the configuration actually exist in our deobfuscated JAR file
                if (struct.innerClasses != null)
                {
                    Iterator<InnerClass> innerClasses = struct.innerClasses.iterator();
                    while (innerClasses.hasNext())
                    {
                        InnerClass innerClass = innerClasses.next();
                        if (zip.getEntry(innerClass.inner_class + ".class") == null)
                        {
                            getLogger().info("Removing unknown inner class {} from {}", innerClass.inner_class, className);
                            innerClasses.remove();
                        }
                    }
                }
            }
        }
        finally
        {
            zip.close();
        }
    }

    public File getExceptorCfg()
    {
        return getProject().file(exceptorCfg);
    }

    public void setExceptorCfg(Object exceptorCfg)
    {
        this.exceptorCfg = exceptorCfg;
    }

    public File getExceptorJson()
    {
        if (exceptorJson == null)
            return null;
        else
            return getProject().file(exceptorJson);
    }

    public void setExceptorJson(Object exceptorJson)
    {
        this.exceptorJson = exceptorJson;
    }

    public boolean isApplyMarkers()
    {
        return applyMarkers;
    }

    public void setApplyMarkers(boolean applyMarkers)
    {
        this.applyMarkers = applyMarkers;
    }

    public File getInJar()
    {
        return getProject().file(inJar);
    }

    public void setInJar(Object inJar)
    {
        this.inJar = inJar;
    }

    public File getLog()
    {
        if (log == null)
            return null;
        else
            return getProject().file(log);
    }

    public void setLog(Object log)
    {
        this.log = log;
    }

    public File getSrg()
    {
        return getProject().file(srg);
    }

    public void setSrg(Object srg)
    {
        this.srg = srg;
    }

    /**
     * returns the actual output file depending on Clean status
     * @return File representing output jar
     */
    @Cached
    @OutputFile
    public File getOutJar()
    {
        return getProject().file(outJar);
    }

    public void setOutJar(Object outJar)
    {
        this.outJar = outJar;
    }

    /**
     * returns the actual output Object depending on Clean status
     * Unlike getOutputJar() this method does not resolve the files.
     * @return Object that will resolve to
     */
    @SuppressWarnings("serial")
    public Closure<File> getDelayedOutput()
    {
        return new Closure<File>(DeobfuscateJar.class) {
            public File call()
            {
                return getOutJar();
            }
        };
    }

    public File getFieldCsv()
    {
        return fieldCsv == null ? null : getProject().file(fieldCsv);
    }

    public void setFieldCsv(Object fieldCsv)
    {
        this.fieldCsv = fieldCsv;
    }

    public File getMethodCsv()
    {
        return methodCsv == null ? null : getProject().file(methodCsv);
    }

    public void setMethodCsv(Object methodCsv)
    {
        this.methodCsv = methodCsv;
    }

    private static final class ErroringRemappingAccessMap extends AccessMap
    {
        private final Map<String, String> renames     = Maps.newHashMap();

        public ErroringRemappingAccessMap(File[] renameCsvs) throws IOException
        {
            super();

            for (File f : renameCsvs)
            {
                if (f == null)
                    continue;
                Files.readLines(f, StandardCharsets.UTF_8, new LineProcessor<String>()
                {
                    @Override
                    public boolean processLine(String line) throws IOException
                    {
                        String[] pts = line.split(",");
                        if (!"searge".equals(pts[0]))
                        {
                            renames.put(pts[0], pts[1]);
                        }

                        return true;
                    }

                    @Override
                    public String getResult()
                    {
                        return null;
                    }
                });
            }
        }

        @Override
        public void loadAccessTransformer(File file) throws IOException
        {
            // because SS doesnt close its freaking reader...
            BufferedReader reader = Files.newReader(file, Constants.CHARSET);
            loadAccessTransformer(reader);
            reader.close();
        }

        @Override
        public void addAccessChange(String symbolString, String accessString)
        {
            String[] pts = symbolString.split(" ");
            if (pts.length >= 2)
            {
                int idx = pts[1].indexOf('(');

                String start = pts[1];
                String end = "";

                if (idx != -1)
                {
                    start = pts[1].substring(0, idx);
                    end = pts[1].substring(idx);
                }

                String rename = renames.get(start);
                if (rename != null)
                {
                    pts[1] = rename + end;
                }
            }
            String joinedString = Joiner.on('.').join(pts);
            super.addAccessChange(joinedString, accessString);
        }

        @Override
        protected void accessApplied(String key, int oldAccess, int newAccess)
        {
        }
    }
}
