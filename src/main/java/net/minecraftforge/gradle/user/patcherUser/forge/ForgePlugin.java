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
package net.minecraftforge.gradle.user.patcherUser.forge;

import static net.minecraftforge.gradle.common.Constants.REPLACE_MC_VERSION;
import static net.minecraftforge.gradle.user.UserConstants.TASK_REOBF;

import java.util.List;

import org.gradle.api.tasks.bundling.Jar;

import com.google.common.base.Strings;
import net.minecraftforge.gradle.common.Constants;
import net.minecraftforge.gradle.tasks.CreateStartTask;
import net.minecraftforge.gradle.user.ReobfMappingType;
import net.minecraftforge.gradle.user.ReobfTaskFactory.ReobfTaskWrapper;
import net.minecraftforge.gradle.user.TaskSingleReobf;
import net.minecraftforge.gradle.user.UserConstants;
import net.minecraftforge.gradle.user.patcherUser.PatcherUserBasePlugin;
import net.minecraftforge.gradle.util.GradleConfigurationException;

public class ForgePlugin extends PatcherUserBasePlugin<ForgeExtension>
{
    @Override
    protected void applyUserPlugin()
    {
        super.applyUserPlugin();

        // setup reobf
        {
            TaskSingleReobf reobf = (TaskSingleReobf) project.getTasks().getByName(TASK_REOBF);
            reobf.addPreTransformer(new McVersionTransformer(delayedString(REPLACE_MC_VERSION)));
        }

        // add coremod loading hack to gradle start
        {
            CreateStartTask makeStart = ((CreateStartTask)project.getTasks().getByName(UserConstants.TASK_MAKE_START));
            for (String res : Constants.GRADLE_START_FML_RES)
            {
                makeStart.addResource(res);
            }
            makeStart.addExtraLine("net.minecraftforge.gradle.GradleForgeHacks.searchCoremods(this);");
        }
    }

    @Override
    protected void setupReobf(ReobfTaskWrapper reobf)
    {
        super.setupReobf(reobf);
        reobf.setMappingType(ReobfMappingType.SEARGE);
    }

    @Override
    protected void afterEvaluate()
    {
        ForgeExtension ext = getExtension();
        if (Strings.isNullOrEmpty(ext.getForgeVersion()))
        {
            throw new GradleConfigurationException("You must set the Forge version!");
        }

        super.afterEvaluate();

        // add manifest things
        {
            Jar jarTask = (Jar) project.getTasks().getByName("jar");

            if (!Strings.isNullOrEmpty(ext.getCoreMod()))
            {
                jarTask.getManifest().getAttributes().put("FMLCorePlugin", ext.getCoreMod());
            }
        }
    }

    @Override
    public String getApiGroup(ForgeExtension ext)
    {
        return "net.minecraftforge";
    }

    @Override
    public String getApiName(ForgeExtension ext)
    {
        return "forge";
    }

    @Override
    public String getApiVersion(ForgeExtension ext)
    {
        return ext.getVersion() + "-" + ext.getForgeVersion();
    }

    @Override
    public String getUserdevClassifier(ForgeExtension ext)
    {
        return "userdev";
    }

    @Override
    public String getUserdevExtension(ForgeExtension ext)
    {
        return "jar";
    }

    @Override
    protected String getClientTweaker(ForgeExtension ext)
    {
        return getApiGroup(ext) + ".fml.common.launcher.FMLTweaker";
    }

    @Override
    protected String getClientRunClass(ForgeExtension ext)
    {
        return "net.minecraft.launchwrapper.Launch";
    }

    @Override
    protected List<String> getClientRunArgs(ForgeExtension ext)
    {
        return ext.getResolvedClientRunArgs();
    }

    @Override
    protected List<String> getClientJvmArgs(ForgeExtension ext)
    {
        List<String> out = ext.getResolvedClientJvmArgs();
        if (!Strings.isNullOrEmpty(ext.getCoreMod()))
        {
            out.add("-Dfml.coreMods.load=" + ext.getCoreMod());
        }
        return out;
    }
}
