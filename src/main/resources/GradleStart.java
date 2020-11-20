import java.io.File;
import java.lang.reflect.Field;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.gson.GsonBuilder;
import com.mojang.authlib.Agent;
import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.properties.PropertyMap;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import com.mojang.authlib.yggdrasil.YggdrasilUserAuthentication;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import joptsimple.NonOptionArgumentSpec;
import joptsimple.OptionParser;
import joptsimple.OptionSet;

public class GradleStart {
    public static final Logger LOGGER = LogManager.getLogger("GradleStart");

    Map<String, String> argMap = Maps.newHashMap();
    List<String> extras = Lists.newArrayList();

    static final File SRG_DIR       = new File("@@SRGDIR@@");
    static final File SRG_NOTCH_SRG = new File("@@SRG_NOTCH_SRG@@");
    static final File SRG_NOTCH_MCP = new File("@@SRG_NOTCH_MCP@@");
    static final File SRG_SRG_MCP   = new File("@@SRG_SRG_MCP@@");
    static final File SRG_MCP_SRG   = new File("@@SRG_MCP_SRG@@");
    static final File SRG_MCP_NOTCH = new File("@@SRG_MCP_NOTCH@@");
    static final File CSV_DIR       = new File("@@CSVDIR@@");

    public static void main(String[] args) throws Throwable {
        hackNatives();
        
        // launch
        (new GradleStart()).launch(args);
    }
    
    protected String getBounceClass() {
        return "@@BOUNCERCLIENT@@";
    }
    
    protected String getTweakClass() {
        return "@@TWEAKERCLIENT@@";
    }
    
    protected void setDefaultArguments(Map<String, String> argMap) {
        argMap.put("version",        "@@MCVERSION@@");
        argMap.put("assetIndex",     "@@ASSETINDEX@@");
        argMap.put("assetsDir",      "@@ASSETSDIR@@");
        argMap.put("accessToken",    "FML");
        argMap.put("userProperties", "{}");
        argMap.put("username",        null);
        argMap.put("password",        null);
    }

    protected void preLaunch(Map<String, String> argMap, List<String> extras) {
        if (!Strings.isNullOrEmpty(argMap.get("password"))) {
            LOGGER.info("Password found, attempting login");
            attemptLogin(argMap);
        }
    }

    private static void hackNatives()
    {
        String paths = System.getProperty("java.library.path");
        String nativesDir = "@@NATIVESDIR@@";
        
        if (Strings.isNullOrEmpty(paths))
            paths = nativesDir;
        else
            paths += File.pathSeparator + nativesDir;
        
        System.setProperty("java.library.path", paths);

        // hack the classloader now.
        try {
            final Field sysPathsField = ClassLoader.class.getDeclaredField("sys_paths");
            sysPathsField.setAccessible(true);
            sysPathsField.set(null, null);
        } catch (Throwable t) {}
    }

    private void attemptLogin(Map<String, String> argMap) {
        YggdrasilUserAuthentication auth = (YggdrasilUserAuthentication) new YggdrasilAuthenticationService(Proxy.NO_PROXY, "1").createUserAuthentication(Agent.MINECRAFT);
        auth.setUsername(argMap.get("username"));
        auth.setPassword(argMap.get("password"));
        argMap.put("password", null);

        try {
            auth.logIn();
        } catch (AuthenticationException e) {
            LOGGER.error("-- Login failed!  " + e.getMessage());
            Throwables.propagate(e);
            return;
        }

        LOGGER.info("Login Succesful!");
        argMap.put("accessToken", auth.getAuthenticatedToken());
        argMap.put("uuid", auth.getSelectedProfile().getId().toString().replace("-", ""));
        argMap.put("username", auth.getSelectedProfile().getName());
        argMap.put("userType", auth.getUserType().getName());
        
        // 1.8 only apperantly.. -_-
        argMap.put("userProperties", new GsonBuilder().registerTypeAdapter(PropertyMap.class, new PropertyMap.Serializer()).create().toJson(auth.getUserProperties()));
    }

    protected void launch(String[] args) throws Throwable {
        // DEPRECATED, use the properties below instead!
        System.setProperty("net.minecraftforge.gradle.GradleStart.srgDir", SRG_DIR.getCanonicalPath());

        // set system vars for passwords
        System.setProperty("net.minecraftforge.gradle.GradleStart.srg.notch-srg", SRG_NOTCH_SRG.getCanonicalPath());
        System.setProperty("net.minecraftforge.gradle.GradleStart.srg.notch-mcp", SRG_NOTCH_MCP.getCanonicalPath());
        System.setProperty("net.minecraftforge.gradle.GradleStart.srg.srg-mcp", SRG_SRG_MCP.getCanonicalPath());
        System.setProperty("net.minecraftforge.gradle.GradleStart.srg.mcp-srg", SRG_MCP_SRG.getCanonicalPath());
        System.setProperty("net.minecraftforge.gradle.GradleStart.srg.mcp-notch", SRG_MCP_NOTCH.getCanonicalPath());
        System.setProperty("net.minecraftforge.gradle.GradleStart.csvDir", CSV_DIR.getCanonicalPath());

        // set defaults!
        setDefaultArguments(argMap);

        // parse stuff
        parseArgs(args);

        // now send it back for prelaunch
        preLaunch(argMap, extras);
        
        //@@EXTRALINES@@

        // now the actual launch args.
        args = getArgs();

        // clear it out
        argMap = null;
        extras = null;

        // launch.
        System.gc();
        Class.forName(getBounceClass()).getDeclaredMethod("main", String[].class).invoke(null, new Object[] { args });
    }

    private String[] getArgs() {
        ArrayList<String> list = new ArrayList<String>(22);

        for (Map.Entry<String, String> e : argMap.entrySet()) {
            String val = e.getValue();
            if (!Strings.isNullOrEmpty(val)) {
                list.add("--" + e.getKey());
                list.add(val);
            }
        }

        // grab tweakClass
        if (!Strings.isNullOrEmpty(getTweakClass())) {
            list.add("--tweakClass");
            list.add(getTweakClass());
        }

        if (extras != null) {
            list.addAll(extras);
        }

        String[] out = list.toArray(new String[list.size()]);

        // final logging.
        StringBuilder b = new StringBuilder();
        b.append('[');
        for (int x = 0; x < out.length; x++)
        {
            b.append(out[x]);
            if ("--accessToken".equalsIgnoreCase(out[x]))
            {
                b.append("{REDACTED}");
                x++;
            }

            if (x < out.length - 1)
            {
                b.append(", ");
            }
        }
        b.append(']');
        LOGGER.info("Running with arguments: " + b.toString());

        return out;
    }

    private void parseArgs(String[] args)
    {
        final OptionParser parser = new OptionParser();
        parser.allowsUnrecognizedOptions();

        for (String key : argMap.keySet())
        {
            parser.accepts(key).withRequiredArg().ofType(String.class);
        }

        final NonOptionArgumentSpec<String> nonOption = parser.nonOptions();

        final OptionSet options = parser.parse(args);
        for (String key : argMap.keySet())
        {
            if (options.hasArgument(key))
            {
                String value = (String) options.valueOf(key);
                argMap.put(key, value);
                if (!"password".equalsIgnoreCase(key))
                    LOGGER.info(key + ": " + value);
            }
        }

        extras = Lists.newArrayList(nonOption.values(options));
        LOGGER.info("Extra: " + extras);
    }
}
