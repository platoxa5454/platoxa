/*
 * The FML Forge Mod Loader suite. Copyright (C) 2012 cpw
 *
 * This library is free software; you can redistribute it and/or modify it under the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with this library; if not, write to the Free Software Foundation, Inc., 51
 * Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */
package cpw.mods.fml.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.client.resources.IResourcePack;
import net.minecraft.crash.CrashReport;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

import cpw.mods.fml.client.registry.KeyBindingRegistry;
import cpw.mods.fml.common.DummyModContainer;
import cpw.mods.fml.common.DuplicateModsFoundException;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.FMLLog;
import cpw.mods.fml.common.IFMLSidedHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.LoaderException;
import cpw.mods.fml.common.MetadataCollection;
import cpw.mods.fml.common.MissingModsException;
import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.ModMetadata;
import cpw.mods.fml.common.ObfuscationReflectionHelper;
import cpw.mods.fml.common.WrongMinecraftVersionException;
import cpw.mods.fml.common.network.packet.EntitySpawnAdjustmentPacket;
import cpw.mods.fml.common.network.packet.EntitySpawnPacket;
import cpw.mods.fml.common.network.packet.ModMissingPacket;
import cpw.mods.fml.common.registry.EntityRegistry.EntityRegistration;
import cpw.mods.fml.common.registry.IEntityAdditionalSpawnData;
import cpw.mods.fml.common.registry.IThrowableEntity;
import cpw.mods.fml.common.toposort.ModSortingException;
import cpw.mods.fml.relauncher.Side;


/**
 * Handles primary communication from hooked code into the system
 *
 * The FML entry point is {@link #beginMinecraftLoading(Minecraft, List)} called from
 * {@link Minecraft}
 *
 * Obfuscated code should focus on this class and other members of the "server"
 * (or "client") code
 *
 * The actual mod loading is handled at arms length by {@link Loader}
 *
 * It is expected that a similar class will exist for each target environment:
 * Bukkit and Client side.
 *
 * It should not be directly modified.
 *
 * @author cpw
 *
 */
public class FMLClientHandler implements IFMLSidedHandler
{
    /**
     * The singleton
     */
    private static final FMLClientHandler INSTANCE = new FMLClientHandler();

    /**
     * A reference to the server itself
     */
    private Minecraft client;

    private DummyModContainer optifineContainer;

    @SuppressWarnings("unused")
    private boolean guiLoaded;

    @SuppressWarnings("unused")
    private boolean serverIsRunning;

    private MissingModsException modsMissing;

    private ModSortingException modSorting;

    private boolean loading = true;

    private WrongMinecraftVersionException wrongMC;

    private CustomModLoadingErrorDisplayException customError;

	private DuplicateModsFoundException dupesFound;

    private boolean serverShouldBeKilledQuietly;

    private List<IResourcePack> resourcePackList;

    @SuppressWarnings("unused")
    private IReloadableResourceManager resourceManager;

    private Map<String, IResourcePack> resourcePackMap;

    private NetworkManager networkConnection;

    /**
     * Called to start the whole game off
     *
     * @param minecraft The minecraft instance being launched
     * @param resourcePackList The resource pack list we will populate with mods
     * @param resourceManager The resource manager
     */
    @SuppressWarnings("unchecked")
    public void beginMinecraftLoading(Minecraft minecraft, @SuppressWarnings("rawtypes") List resourcePackList, IReloadableResourceManager resourceManager)
    {
        client = minecraft;
        this.resourcePackList = resourcePackList;
        this.resourceManager = resourceManager;
        this.resourcePackMap = Maps.newHashMap();
        if (minecraft.func_71355_q())
        {
            FMLLog.severe("DEMO MODE DETECTED, FML will not work. Finishing now.");
            haltGame("FML will not run in demo mode", new RuntimeException());
            return;
        }

        FMLCommonHandler.instance().beginLoading(this);
        try
        {
            Class<?> optifineConfig = Class.forName("Config", false, Loader.instance().getModClassLoader());
            String optifineVersion = (String) optifineConfig.getField("VERSION").get(null);
            Map<String,Object> dummyOptifineMeta = ImmutableMap.<String,Object>builder().put("name", "Optifine").put("version", optifineVersion).build();
            ModMetadata optifineMetadata = MetadataCollection.from(getClass().getResourceAsStream("optifinemod.info"),"optifine").getMetadataForId("optifine", dummyOptifineMeta);
            optifineContainer = new DummyModContainer(optifineMetadata);
            FMLLog.info("Forge Mod Loader has detected optifine %s, enabling compatibility features",optifineContainer.getVersion());
        }
        catch (Exception e)
        {
            optifineContainer = null;
        }
        try
        {
            Loader.instance().loadMods();
        }
        catch (WrongMinecraftVersionException wrong)
        {
            wrongMC = wrong;
        }
        catch (DuplicateModsFoundException dupes)
        {
        	dupesFound = dupes;
        }
        catch (MissingModsException missing)
        {
            modsMissing = missing;
        }
        catch (ModSortingException sorting)
        {
            modSorting = sorting;
        }
        catch (CustomModLoadingErrorDisplayException custom)
        {
            FMLLog.log(Level.SEVERE, custom, "A custom exception was thrown by a mod, the game will now halt");
            customError = custom;
        }
        catch (LoaderException le)
        {
            haltGame("There was a severe problem during mod loading that has caused the game to fail", le);
            return;
        }

        Map<String,Map<String,String>> sharedModList = (Map<String, Map<String, String>>) Launch.blackboard.get("modList");
        if (sharedModList == null)
        {
            sharedModList = Maps.newHashMap();
            Launch.blackboard.put("modList", sharedModList);
        }
        for (ModContainer mc : Loader.instance().getActiveModList())
        {
            Map<String,String> sharedModDescriptor = mc.getSharedModDescriptor();
            if (sharedModDescriptor != null)
            {
                String sharedModId = "fml:"+mc.getModId();
                sharedModList.put(sharedModId, sharedModDescriptor);
            }
        }
    }

    @Override
    public void haltGame(String message, Throwable t)
    {
        client.func_71377_b(new CrashReport(message, t));
        throw Throwables.propagate(t);
    }
    /**
     * Called a bit later on during initialization to finish loading mods
     * Also initializes key bindings
     *
     */
    @SuppressWarnings({ "deprecation" })
    public void finishMinecraftLoading()
    {
        if (modsMissing != null || wrongMC != null || customError!=null || dupesFound!=null || modSorting!=null)
        {
            return;
        }
        try
        {
            Loader.instance().initializeMods();
        }
        catch (CustomModLoadingErrorDisplayException custom)
        {
            FMLLog.log(Level.SEVERE, custom, "A custom exception was thrown by a mod, the game will now halt");
            customError = custom;
            return;
        }
        catch (LoaderException le)
        {
            haltGame("There was a severe problem during mod loading that has caused the game to fail", le);
            return;
        }

        // Reload resources
        client.func_110436_a();
        loading = false;
        KeyBindingRegistry.instance().uploadKeyBindingsToGame(client.field_71474_y);
    }

    @SuppressWarnings("unused")
    public void extendModList()
    {
        @SuppressWarnings("unchecked")
        Map<String,Map<String,String>> modList = (Map<String, Map<String, String>>) Launch.blackboard.get("modList");
        if (modList != null)
        {
            for (Entry<String, Map<String, String>> modEntry : modList.entrySet())
            {
                String sharedModId = modEntry.getKey();
                String system = sharedModId.split(":")[0];
                if ("fml".equals(system))
                {
                    continue;
                }
                Map<String, String> mod = modEntry.getValue();
                String modSystem = mod.get("modsystem"); // the modsystem (FML uses FML or ModLoader)
                String modId = mod.get("id"); // unique ID
                String modVersion = mod.get("version"); // version
                String modName = mod.get("name"); // a human readable name
                String modURL = mod.get("url"); // a URL for the mod (can be empty string)
                String modAuthors = mod.get("authors"); // a csv of authors (can be empty string)
                String modDescription = mod.get("description"); // a (potentially) multiline description (can be empty string)
            }
        }

    }
    public void onInitializationComplete()
    {
        if (wrongMC != null)
        {
            showGuiScreen(new GuiWrongMinecraft(wrongMC));
        }
        else if (modsMissing != null)
        {
            showGuiScreen(new GuiModsMissing(modsMissing));
        }
        else if (dupesFound != null)
        {
            showGuiScreen(new GuiDupesFound(dupesFound));
        }
        else if (modSorting != null)
        {
            showGuiScreen(new GuiSortingProblem(modSorting));
        }
		else if (customError != null)
        {
		    showGuiScreen(new GuiCustomModLoadingErrorScreen(customError));
        }
        else
        {
        }
    }
    /**
     * Get the server instance
     */
    public Minecraft getClient()
    {
        return client;
    }

    /**
     * Get a handle to the client's logger instance
     * The client actually doesn't have one- so we return null
     */
    public Logger getMinecraftLogger()
    {
        return null;
    }

    /**
     * @return the instance
     */
    public static FMLClientHandler instance()
    {
        return INSTANCE;
    }

    /**
     * @param player
     * @param gui
     */
    public void displayGuiScreen(EntityPlayer player, GuiScreen gui)
    {
        if (client.field_71439_g==player && gui != null) {
            client.func_147108_a(gui);
        }
    }

    /**
     * @param mods
     */
    public void addSpecialModEntries(ArrayList<ModContainer> mods)
    {
        if (optifineContainer!=null) {
            mods.add(optifineContainer);
        }
    }

    @Override
    public List<String> getAdditionalBrandingInformation()
    {
        if (optifineContainer!=null)
        {
            return Arrays.asList(String.format("Optifine %s",optifineContainer.getVersion()));
        } else {
            return ImmutableList.<String>of();
        }
    }

    @Override
    public Side getSide()
    {
        return Side.CLIENT;
    }

    public boolean hasOptifine()
    {
        return optifineContainer!=null;
    }

    @Override
    public void showGuiScreen(Object clientGuiElement)
    {
        GuiScreen gui = (GuiScreen) clientGuiElement;
        client.func_147108_a(gui);
    }

    @SuppressWarnings("rawtypes")
    @Override
    public Entity spawnEntityIntoClientWorld(EntityRegistration er, EntitySpawnPacket packet)
    {
        WorldClient wc = client.field_71441_e;

        Class<? extends Entity> cls = er.getEntityClass();

        try
        {
            Entity entity;
            if (er.hasCustomSpawning())
            {
                entity = er.doCustomSpawning(packet);
            }
            else
            {
                entity = (Entity)(cls.getConstructor(World.class).newInstance(wc));
                int offset = packet.entityId - entity.func_145782_y();
                entity.func_145769_d(packet.entityId);
                entity.func_70012_b(packet.scaledX, packet.scaledY, packet.scaledZ, packet.scaledYaw, packet.scaledPitch);
                if (entity instanceof EntityLiving)
                {
                    ((EntityLiving)entity).field_70759_as = packet.scaledHeadYaw;
                }

                Entity parts[] = entity.func_70021_al();
                if (parts != null)
                {
                    for (int j = 0; j < parts.length; j++)
                    {
                        parts[j].func_145769_d(parts[j].func_145782_y() + offset);
                    }
                }
            }

            entity.field_70118_ct = packet.rawX;
            entity.field_70117_cu = packet.rawY;
            entity.field_70116_cv = packet.rawZ;

            if (entity instanceof IThrowableEntity)
            {
                Entity thrower = client.field_71439_g.func_145782_y() == packet.throwerId ? client.field_71439_g : wc.func_73045_a(packet.throwerId);
                ((IThrowableEntity)entity).setThrower(thrower);
            }

            if (packet.metadata != null)
            {
                entity.func_70096_w().func_75687_a((List)packet.metadata);
            }

            if (packet.throwerId > 0)
            {
                entity.func_70016_h(packet.speedScaledX, packet.speedScaledY, packet.speedScaledZ);
            }

            if (entity instanceof IEntityAdditionalSpawnData)
            {
                ((IEntityAdditionalSpawnData)entity).readSpawnData(packet.dataStream);
            }

            wc.func_73027_a(packet.entityId, entity);
            return entity;
        }
        catch (Exception e)
        {
            FMLLog.log(Level.SEVERE, e, "A severe problem occurred during the spawning of an entity");
            throw Throwables.propagate(e);
        }
    }

    @Override
    public void adjustEntityLocationOnClient(EntitySpawnAdjustmentPacket packet)
    {
        Entity ent = client.field_71441_e.func_73045_a(packet.entityId);
        if (ent != null)
        {
            ent.field_70118_ct = packet.serverX;
            ent.field_70117_cu = packet.serverY;
            ent.field_70116_cv = packet.serverZ;
        }
        else
        {
            FMLLog.fine("Attempted to adjust the position of entity %d which is not present on the client", packet.entityId);
        }
    }

    @Override
    public void beginServerLoading(MinecraftServer server)
    {
        serverShouldBeKilledQuietly = false;
        // NOOP
    }

    @Override
    public void finishServerLoading()
    {
        // NOOP
    }

    @Override
    public MinecraftServer getServer()
    {
        return client.func_71401_C();
    }

    @Override
    public void displayMissingMods(ModMissingPacket modMissingPacket)
    {
        showGuiScreen(new GuiModsMissingForServer(modMissingPacket));
    }

    /**
     * If the client is in the midst of loading, we disable saving so that custom settings aren't wiped out
     */
    public boolean isLoading()
    {
        return loading;
    }

    @Override
    public boolean shouldServerShouldBeKilledQuietly()
    {
        return serverShouldBeKilledQuietly;
    }

    /**
     * Is this GUI type open?
     *
     * @param gui The type of GUI to test for
     * @return if a GUI of this type is open
     */
    public boolean isGUIOpen(Class<? extends GuiScreen> gui)
    {
        return client.field_71462_r != null && client.field_71462_r.getClass().equals(gui);
    }


    @Override
    public void addModAsResource(ModContainer container)
    {
        Class<?> resourcePackType = container.getCustomResourcePackClass();
        if (resourcePackType != null)
        {
            try
            {
                IResourcePack pack = (IResourcePack) resourcePackType.getConstructor(ModContainer.class).newInstance(container);
                resourcePackList.add(pack);
                resourcePackMap.put(container.getModId(), pack);
            }
            catch (NoSuchMethodException e)
            {
                FMLLog.log(Level.SEVERE, "The container %s (type %s) returned an invalid class for it's resource pack.", container.getName(), container.getClass().getName());
                return;
            }
            catch (Exception e)
            {
                FMLLog.log(Level.SEVERE, e, "An unexpected exception occurred constructing the custom resource pack for %s", container.getName());
                throw Throwables.propagate(e);
            }
        }
    }

    @Override
    public void updateResourcePackList()
    {
        client.func_110436_a();
    }

    public IResourcePack getResourcePackFor(String modId)
    {
        return resourcePackMap.get(modId);
    }

    @Override
    public String getCurrentLanguage()
    {
        return client.func_135016_M().func_135041_c().func_135034_a();
    }

    @Override
    public void serverStopped()
    {
        // If the server crashes during startup, it might hang the client- reset the client so it can abend properly.
        MinecraftServer server = getServer();

        if (server != null && !server.func_71200_ad())
        {
            ObfuscationReflectionHelper.setPrivateValue(MinecraftServer.class, server, true, "field_71296"+"_Q","serverIs"+"Running");
        }
    }

    @Override
    public NetworkManager getClientToServerNetworkManager()
    {
        return this.networkConnection;
    }

    public void currentConnection(NetworkManager networkManager, boolean shouldChange)
    {
        if (shouldChange) this.networkConnection = networkManager;
    }
}