package noobanidus.mods.deathbackup;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.command.CommandException;
import net.minecraft.command.CommandSource;
import net.minecraft.command.Commands;
import net.minecraft.command.ISuggestionProvider;
import net.minecraft.command.arguments.EntityArgument;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.server.FMLServerStartingEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Mod("deathbackup")
public class DeathBackup {
  public static final Logger LOG = LogManager.getLogger();
  public static final String MODID = "deathbackup";
  public static boolean registered = false;

  public static List<String> KEYS_TO_REMOVE = Arrays.asList("abilities", "Attributes", "EnderItems", "Motion", "Pos", "Rotation", "Spawns", "AbsorptionAmount", "Air", "CanUpdate", "DataVersion", "DeathTime", "Dimension", "FallDistance", "FallFlying", "Fire", "foodExhaustionLevel", "foodLevel", "foodSaturationLevel", "foodTickTimer", "Health", "HurtByTimestamp", "HurtTime", "Invulnerable", "OnGround", "playerGameType", "PortalCooldown", "Score", "SleepTimer", "UUIDLeast", "UUIDMost");

  public static CommandRestore RESTORE_COMMAND = null;

  public DeathBackup() {
    MinecraftForge.EVENT_BUS.addListener(DeathBackup::onServerStarting);
  }

  public static void ensureFirst() {
    if (!registered) {
      MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, true, DeathBackup::onPlayerDeath);
      registered = true;
    }
  }

  public static void onPlayerDeath(LivingDeathEvent event) {
    if (event.getEntityLiving() instanceof PlayerEntity && event.getEntityLiving().isServerWorld()) {
      ServerPlayerEntity old = (ServerPlayerEntity) event.getEntityLiving();
      String playerName = old.getScoreboardName().toLowerCase();
      ServerWorld world = old.getServerWorld();
      CompoundNBT tag = old.writeWithoutTypeId(new CompoundNBT());
      String hex = Long.toHexString(world.getGameTime());
      String timestamp = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss").format(new Date());
      String identifier = playerName + "." + hex + "." + timestamp;
      String fileName = identifier + ".nbt";
      File dir = new File(old.server.getWorld(DimensionType.OVERWORLD).getSaveHandler().getWorldDirectory(), "death-backups");
      if (!dir.exists()) {
        if (!dir.mkdir()) {
          LOG.error("Unable to create death-backups folder.");
        }
      }
      File file = new File(dir, fileName);
      try {
        if (!file.exists()) {
          try (FileOutputStream output = new FileOutputStream(file)) {
            CompressedStreamTools.writeCompressed(tag, output);
          }
        }
      } catch (IOException e) {
        LOG.error("Unable to save player death data for " + playerName, e);
        return;
      }

      LOG.info("Saved player death data to: " + fileName + ". To restore, type " + TextFormatting.BOLD + "/db restore " + playerName + " " + timestamp + TextFormatting.RESET);
    }
  }

  public static void onServerStarting(FMLServerStartingEvent event) {
    ensureFirst();
    RESTORE_COMMAND = new CommandRestore(event.getCommandDispatcher());
    RESTORE_COMMAND.register();
  }

  public static class CommandRestore {
    private final CommandDispatcher<CommandSource> commandDispatcher;

    public CommandRestore(CommandDispatcher<CommandSource> commandDispatcher) {
      this.commandDispatcher = commandDispatcher;
    }

    public void register() {
      this.commandDispatcher.register(builder(Commands.literal("db").requires(p -> p.hasPermissionLevel(2))));
    }

    LiteralArgumentBuilder<CommandSource> builder(LiteralArgumentBuilder<CommandSource> builder) {
      builder.executes(c -> {
        c.getSource().sendFeedback(new StringTextComponent("/db restore <player timestamp> <backup> | use tab complete for ease of use, player must be online"), false);
        return 1;
      });
      builder.then(Commands.literal("restore").executes(c -> {
        c.getSource().sendFeedback(new StringTextComponent("/db restore <player timestamp> <backup> | use tab complete for ease of use, player must be online"), false);
        return 1;
      }).then(Commands.argument("player", EntityArgument.player()).executes(c -> {
        ServerPlayerEntity player = EntityArgument.getPlayer(c, "player");
        return restorePlayer(c.getSource(), player, null);
      }).then(Commands.argument("file", StringArgumentType.word()).suggests((c, build) -> {
        ServerPlayerEntity player = EntityArgument.getPlayer(c, "player");
        return ISuggestionProvider.suggest(getFilenamesFor(c.getSource(), player).stream().map(FileName::getTimestamp).collect(Collectors.toList()), build);
      }).executes(c -> {
        ServerPlayerEntity player = EntityArgument.getPlayer(c, "player");
        String filename = StringArgumentType.getString(c, "file");
        return restorePlayer(c.getSource(), player, filename);
      }))));

      return builder;
    }

    public int restorePlayer(CommandSource sender, ServerPlayerEntity player, @Nullable String filename) {
      FileName f = null;
      List<FileName> files = getFilenamesFor(sender, player);
      if (files.isEmpty()) {
        // TODO: Error here to say no files available
        // TODO: Hopefully already handled
        throw new CommandException(new StringTextComponent("No back-ups found for " + player.getScoreboardName()));
      }
      if (filename == null) {
        f = files.get(0); // The most recent one
        filename = f.getTimestamp();
      } else {
        for (FileName file : files) {
          if (file.getTimestamp().equals(filename)) {
            f = file;
            break;
          }
        }
      }
      if (f == null) {
        // TODO: Error here to report that there was no file to restore
        throw new CommandException(new StringTextComponent("Unable to restore " + (filename == null ? "latest back-up" : "back-up " + filename)));
      } else {
        File fn = f.getFile();
        CompoundNBT incoming;
        try (FileInputStream input = new FileInputStream(fn)) {
          incoming = CompressedStreamTools.readCompressed(input);
        } catch (Exception e) {
          // TODO: Error here to report that the file couldn't be read
          throw new CommandException(new StringTextComponent("Error reading " + fn.getName()));
        }
        for (String key : KEYS_TO_REMOVE) {
          incoming.remove(key);
        }
        CompoundNBT current = player.writeWithoutTypeId(new CompoundNBT());
        for (String key : incoming.keySet()) {
          current.put(key, incoming.get(key));
        }
        player.read(current);
        sender.sendFeedback(new StringTextComponent("Restored " + player.getScoreboardName() + " to back-up: " + filename), false);
        try {
          if (!sender.asPlayer().equals(player)) {
            player.sendMessage(new StringTextComponent(sender.getName() + " restored your inventory from back-up " + TextFormatting.BOLD + filename + TextFormatting.RESET + "."));
          }
        } catch (CommandSyntaxException ignored) {
        }
        return 1;
      }
    }

    public List<FileName> getFilenamesFor(CommandSource source, ServerPlayerEntity player) {
      File backupDir = new File(player.getServer().getWorld(DimensionType.OVERWORLD).getSaveHandler().getWorldDirectory(), "death-backups");
      if (!backupDir.exists()) {
        // TODO: Error here to say no backup directory
        throw new CommandException(new StringTextComponent("Unable to locate" + TextFormatting.BOLD + "death-backups" + TextFormatting.RESET + " folder"));
      }
      String playerName = player.getScoreboardName().toLowerCase();
      File[] files = backupDir.listFiles(p -> p.isFile() && p.getName().endsWith(".nbt") && p.getName().startsWith(playerName));
      if (files == null) {
        // TODO: Error here to say no files available
        throw new CommandException(new StringTextComponent("No back-ups found for " + TextFormatting.BOLD + player.getScoreboardName() + TextFormatting.RESET));
      }
      return Stream.of(files).filter(f -> f.getName().split("\\.").length == 4).map(f -> {
        String[] names = f.getName().split("\\.");
        String timestamp = names[2];
        String hex = names[1];
        return new FileName(f, timestamp, Long.valueOf(hex, 16));
      }).sorted((o1, o2) -> Long.compare(o2.getValue(), o1.getValue())).collect(Collectors.toList());
    }
  }

  public static class FileName {
    private long value;
    private File file;
    private String timestamp;

    public FileName(File file, String timestamp, long value) {
      this.value = value;
      this.file = file;
      this.timestamp = timestamp;
    }

    public File getFile() {
      return file;
    }

    public long getValue() {
      return value;
    }

    public String getTimestamp() {
      return timestamp;
    }
  }
}
