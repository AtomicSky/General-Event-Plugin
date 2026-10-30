package dev.gep;

import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.wesjd.anvilgui.AnvilGUI;

final class ConfigMenu implements Listener, InventoryHolder {
    final GEP p; final UUID owner; final String path; final int page; Inventory inventory;
    final Map<Integer,String> keys = new HashMap<>();
    ConfigMenu(GEP p) { this(p,null,"",0); }
    ConfigMenu(GEP p,UUID owner,String path,int page) { this.p=p;this.owner=owner;this.path=path;this.page=page; }
    public Inventory getInventory(){return inventory;}
    static void open(GEP p,Player player,int page){show(p,player,"",page);}
    static String label(String key){
        String words=key.substring(key.lastIndexOf('.')+1).replace('-', ' ');
        return Arrays.stream(words.split(" ")).map(w->w.isEmpty()?w:Character.toUpperCase(w.charAt(0))+w.substring(1)).collect(java.util.stream.Collectors.joining(" "));
    }
    static Material settingIcon(String key){
        if(key.endsWith("radius-blocks"))return Material.COMPASS;
        if(key.endsWith("mode"))return Material.TOTEM_OF_UNDYING;
        if(key.endsWith("ban-message"))return Material.OAK_SIGN;
        if(key.endsWith("messages"))return Material.WRITABLE_BOOK;
        if(key.endsWith("allowed-commands"))return Material.COMMAND_BLOCK;
        if(key.endsWith("cooldown-seconds"))return Material.CLOCK;
        if(key.endsWith("prompt-timeout-seconds"))return Material.SAND;
        if(key.endsWith("history-size"))return Material.ENDER_PEARL;
        if(key.endsWith("max-entries-per-player"))return Material.BOOKSHELF;
        if(key.endsWith("staff-see-all"))return Material.SPYGLASS;
        if(key.endsWith("staff-exempt"))return Material.GOLDEN_HELMET;
        if(key.endsWith("hidden-visits"))return Material.POTION;
        if(key.endsWith("block-chat"))return Material.PAPER;
        if(key.endsWith("block-commands"))return Material.REPEATING_COMMAND_BLOCK;
        if(key.endsWith("block-teleports"))return Material.ENDER_EYE;
        if(key.endsWith("block-spectating-entities"))return Material.CARVED_PUMPKIN;
        if(key.endsWith("block-private-messages"))return Material.WRITABLE_BOOK;
        if(key.endsWith("block-interactions"))return Material.IRON_BARS;
        if(key.endsWith("prevent-damage"))return Material.SHIELD;
        if(key.endsWith("notify-on-denial"))return Material.BELL;
        if(key.endsWith("message-cooldown-seconds"))return Material.REPEATER;
        String category=key.replace(".enabled","");
        return switch(category){
            case "local-chat"->Material.OAK_SIGN; case "death-messages"->Material.SKELETON_SKULL;
            case "death-lightning"->Material.LIGHTNING_ROD; case "elimination"->Material.TOTEM_OF_UNDYING;
            case "spectator-restrictions"->Material.GLASS; case "chat-mute"->Material.NOTE_BLOCK;
            case "freeze"->Material.PACKED_ICE; case "target-selectors"->Material.TARGET;
            case "teleport-restrictions"->Material.END_STONE; case "vanish"->Material.GLASS_BOTTLE;
            case "staff-requests"->Material.BELL; case "staff-requests.lore"->Material.WRITABLE_BOOK;
            case "staff-requests.kill"->Material.IRON_SWORD; case "staff-requests.materials"->Material.BRICKS;
            case "staff-requests.support"->Material.LANTERN; case "staff-requests.claiming"->Material.NAME_TAG;
            case "back"->Material.ENDER_PEARL; case "private-message-spy"->Material.SPYGLASS;
            case "audit"->Material.WRITTEN_BOOK; case "history"->Material.BOOKSHELF;
            default->Material.COMPARATOR;
        };
    }
    static String description(String key){
        if(key.endsWith("enabled"))return "Turn this feature on or off.";
        if(key.endsWith("radius-blocks"))return "Maximum local chat distance, in blocks.";
        if(key.endsWith("mode"))return "Death outcome: spectator or ban.";
        if(key.endsWith("messages"))return "Death announcements; include <player>.";
        if(key.endsWith("allowed-commands"))return "Commands spectators are allowed to use.";
        if(key.endsWith("cooldown-seconds"))return "Delay between new requests, in seconds.";
        if(key.endsWith("prompt-timeout-seconds"))return "Time allowed to enter a kill target.";
        if(key.endsWith("history-size"))return "How many teleport origins /back remembers.";
        if(key.endsWith("hidden-visits"))return "Automatically vanish for lore visits.";
        return "Configure "+label(key).toLowerCase(Locale.ROOT)+".";
    }
    static ItemStack icon(Material type,String name,String... lore){
        var item=new ItemStack(type);var meta=item.getItemMeta();
        meta.displayName(Component.text(name,NamedTextColor.GOLD).decoration(TextDecoration.ITALIC,false));
        meta.lore(Arrays.stream(lore).map(line->Component.text(line,line.startsWith("Click")?NamedTextColor.YELLOW:line.startsWith("Current: ON")?NamedTextColor.GREEN:line.startsWith("Current: OFF")?NamedTextColor.RED:NamedTextColor.GRAY).decoration(TextDecoration.ITALIC,false)).toList());
        item.setItemMeta(meta);return item;
    }
    static void show(GEP p,Player player,String path,int page){
        GEP.require(player,"config");var menu=new ConfigMenu(p,player.getUniqueId(),path,page);
        menu.inventory=Bukkit.createInventory(menu,54,Component.text(path.isEmpty()?"GEP settings":"Settings: "+path));
        var section=path.isEmpty()?p.getConfig():p.getConfig().getConfigurationSection(path);
        if(section==null)return;
        var names=new ArrayList<>(section.getKeys(false));
        for(int slot=0;slot<45 && page*45+slot<names.size();slot++){
            String key=names.get(page*45+slot),full=path.isEmpty()?key:path+"."+key;Object value=p.getConfig().get(full);menu.keys.put(slot,full);
            boolean group=p.getConfig().isConfigurationSection(full);
            String display=value instanceof Boolean b?(b?"ON":"OFF"):String.valueOf(value);
            if(display.length()>60)display=display.substring(0,57)+"...";
            menu.inventory.setItem(slot,icon(settingIcon(full),label(full),group?"Feature settings":description(full),group?"":"Current: "+display,"",group?"Click to open":value instanceof Boolean?"Click to toggle":"Click to edit in an anvil"));
        }
        if(page>0)menu.inventory.setItem(45,icon(Material.ARROW,"Previous page"));
        menu.inventory.setItem(49,icon(Material.OAK_DOOR,path.isEmpty()?"Close":"Back"));
        if((page+1)*45<names.size())menu.inventory.setItem(53,icon(Material.ARROW,"Next page"));
        player.openInventory(menu.inventory);
    }
    static void change(GEP p,Player player,String key,Object value){
        GEP.require(player,"config");Object old=p.getConfig().get(key);p.getConfig().set(key,value);
        try{p.validate();
            java.nio.file.Path target=p.getDataFolder().toPath().resolve("config.yml"),temp=target.resolveSibling("config.yml.tmp");
            java.nio.file.Files.writeString(temp,p.getConfig().saveToString(),java.nio.charset.StandardCharsets.UTF_8);
            try{java.nio.file.Files.move(temp,target,java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);}
            catch(java.nio.file.AtomicMoveNotSupportedException ex){java.nio.file.Files.move(temp,target,java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
        }
        catch(Exception ex){p.getConfig().set(key,old);throw new IllegalArgumentException("Not saved: "+ex.getMessage());}
        p.requests.tick();for(Player online:Bukkit.getOnlinePlayers())p.rules.enforce(online);
        p.store.audit(player.getName(),"CONFIG",key+" changed");GEP.tell(player,"Saved "+key+".");
    }
    void edit(Player player,String key){
        Object old=p.getConfig().get(key);
        if(String.valueOf(old).length()>50)throw new IllegalArgumentException("This value is too long for a vanilla anvil. Edit config.yml to preserve its full text.");
        new AnvilGUI.Builder().plugin(p).disableGeyserCompat().title("Enter setting value").text(old instanceof List?String.join(" | ",p.getConfig().getStringList(key)):String.valueOf(old))
        .onClick((slot,state)->{
            if(slot!=AnvilGUI.Slot.OUTPUT)return List.of();
            try{
                if(p.rules.noInteract(player))throw new IllegalArgumentException("You cannot edit settings while frozen.");
                String text=state.getText();Object value=text;
                if(old instanceof Integer)value=Integer.parseInt(text.trim());
                else if(old instanceof Number){double number=Double.parseDouble(text.trim());if(!Double.isFinite(number))throw new IllegalArgumentException("Enter a finite number.");value=number;}
                else if(old instanceof List)value=text.isBlank()?List.of():Arrays.stream(text.split("\\|",-1)).map(String::strip).toList();
                change(p,player,key,value);
                return List.of(AnvilGUI.ResponseAction.close(),AnvilGUI.ResponseAction.run(()->p.getServer().getScheduler().runTask(p,()->show(p,player,path,page))));
            }catch(IllegalArgumentException ex){GEP.tell(player,ex instanceof NumberFormatException?"Enter a valid number.":ex.getMessage());return List.of();}
        }).open(player);
        GEP.tell(player,old instanceof List?"Separate list entries with |. Click the result to save; Escape cancels.":"Click the result to save; Escape cancels.");
    }
    @EventHandler(priority=EventPriority.HIGHEST) void click(InventoryClickEvent event){
        if(!(event.getView().getTopInventory().getHolder() instanceof ConfigMenu menu))return;
        event.setCancelled(true);if(!(event.getWhoClicked() instanceof Player player)||!player.getUniqueId().equals(menu.owner))return;
        int slot=event.getRawSlot();if(slot<0||slot>=54||menu.inventory.getItem(slot)==null)return;
        p.getServer().getScheduler().runTask(p,()->{
            try{
                GEP.require(player,"config");if(!player.isOnline() || !player.getOpenInventory().getTopInventory().equals(menu.inventory) || p.rules.noInteract(player))return;
                if(slot==49){if(menu.path.isEmpty())player.closeInventory();else show(p,player,menu.path.contains(".")?menu.path.substring(0,menu.path.lastIndexOf('.')):"",0);return;}
                if(slot==45&&menu.page>0){show(p,player,menu.path,menu.page-1);return;}
                if(slot==53){show(p,player,menu.path,menu.page+1);return;}
                String key=menu.keys.get(slot);if(key==null)return;
                if(p.getConfig().isConfigurationSection(key))show(p,player,key,0);
                else if(p.getConfig().get(key) instanceof Boolean b){change(p,player,key,!b);show(p,player,menu.path,menu.page);}
                else menu.edit(player,key);
            }catch(IllegalArgumentException ex){GEP.tell(player,ex.getMessage());}
        });
    }
    @EventHandler void drag(InventoryDragEvent event){if(event.getView().getTopInventory().getHolder() instanceof ConfigMenu)event.setCancelled(true);}
}
