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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.google.common.collect.Lists;

import net.minecraftforge.gradle.common.BaseExtension;
import net.minecraftforge.gradle.common.Constants;

public class UserBaseExtension extends BaseExtension
{
    private HashMap<String, Object> replacements     = new HashMap<String, Object>();
    private ArrayList<String>       includes         = new ArrayList<String>();
    private String                  runDir           = "run";
    private List<Object>            clientJvmArgs    = Lists.newArrayList();
    private List<Object>            clientRunArgs    = Lists.newArrayList();

    public UserBaseExtension(UserBasePlugin<? extends UserBaseExtension> plugin)
    {
        super(plugin);
    }

    /**
     * Add a source replacement mapping
     *
     * @param token       The token to replace
     * @param replacement The value to replace with
     */
    public void replace(Object token, Object replacement)
    {
        replacements.put(token.toString(), replacement);
    }

    /**
     * Add a map of source replacement mappings
     *
     * @param map A map of tokens -&gt; replacements
     */
    public void replace(Map<Object, Object> map)
    {
        for (Entry<Object, Object> e : map.entrySet())
        {
            replace(e.getKey(), e.getValue());
        }
    }

    /**
     * Get all of the source replacement tokens and values
     *
     * @return A map of tokens -&gt; replacements
     */
    public Map<String, Object> getReplacements()
    {
        return replacements;
    }

    /**
     * Get a list of file patterns that will be used to determine included source replacement files.
     *
     * @return A list of classes
     */
    public List<String> getIncludes()
    {
        return includes;
    }

    /**
     * Set the run location for Minecraft
     *
     * @param value The run location
     */
    public void setRunDir(String value)
    {
        this.runDir = value;
        replacer.putReplacement(UserConstants.REPLACE_RUN_DIR, runDir);
    }

    /**
     * Get the run location for Minecraft
     *
     * @return The run location
     */
    public String getRunDir()
    {
        return this.runDir;
    }

    /**
     * Get the VM arguments for the client run config
     *
     * @return The client JVM args
     */
    public List<Object> getClientJvmArgs()
    {
        return clientJvmArgs;
    }

    public List<String> getResolvedClientJvmArgs()
    {
        return resolve(getClientJvmArgs());
    }

    /**
     * Set the VM arguments for the client run config
     *
     * @param clientJvmArgs The client JVM args
     */
    public void setClientJvmArgs(List<Object> clientJvmArgs)
    {
        this.clientJvmArgs = clientJvmArgs;
    }

    /**
     * Get the run arguments for the client run config
     *
     * @return The client run args
     */
    public List<Object> getClientRunArgs()
    {
        return clientRunArgs;
    }

    public List<String> getResolvedClientRunArgs()
    {
        return resolve(getClientRunArgs());
    }

    /**
     * Set the run arguments for the client run config
     *
     * @param clientRunArgs The client run args
     */
    public void setClientRunArgs(List<Object> clientRunArgs)
    {
        this.clientRunArgs = clientRunArgs;
    }

    private List<String> resolve(List<Object> list)
    {
        List<String> out = Lists.newArrayListWithCapacity(list.size());
        for (Object o : list)
        {
            out.add(Constants.resolveString(o));
        }
        return out;
    }
}
