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

final class RequestDashboard implements Listener, InventoryHolder {
    final GEP p; final UUID owner, ticket; final int page;
    Inventory inventory; final Map<Integer,UUID> entries=new HashMap<>();
    RequestDashboard(GEP p){this(p,null,null,0);}
    RequestDashboard(GEP p,UUID owner,UUID ticket,int page){this.p=p;this.owner=owner;this.ticket=ticket;this.page=page;}
    public Inventory getInventory(){return inventory;}
    static ItemStack item(Material material,String name,String... lines){
        var item=new ItemStack(material);var meta=item.getItemMeta();
        meta.displayName(Component.text(name,NamedTextColor.GOLD).decoration(TextDecoration.ITALIC,false));
        meta.lore(Arrays.stream(lines).map(line->Component.text(line,NamedTextColor.GRAY).decoration(TextDecoration.ITALIC,false)).toList());
        item.setItemMeta(meta);return item;
    }
    static void check(GEP p,Player player){GEP.require(player,"requests.dashboard");GEP.require(player,"requests.view");p.feature("staff-requests");p.feature("staff-requests.dashboard");}
    static void open(GEP p,Player player,int requestedPage){
        check(p,player);
        var tickets=p.requests.tickets().stream().filter(t->t.result().equals("PENDING")&&p.requests.enabled(t.kind()))
            .sorted(Comparator.comparingLong(t->p.store.data.getLong(p.requests.root(t.id())+"created"))).toList();
        int page=Math.max(0,Math.min(requestedPage,Math.max(0,(tickets.size()-1)/45)));
        var menu=new RequestDashboard(p,player.getUniqueId(),null,page);
        menu.inventory=Bukkit.createInventory(menu,54,Component.text("Requests - Page "+(page+1)));
        for(int slot=0;slot<45&&page*45+slot<tickets.size();slot++){
            var t=tickets.get(page*45+slot);menu.entries.put(slot,t.id());
            long minutes=Math.max(0,(System.currentTimeMillis()-p.store.data.getLong(p.requests.root(t.id())+"created"))/60000);
            String claim=t.claim().isEmpty()?"Unclaimed":p.store.name(UUID.fromString(t.claim()));
            menu.inventory.setItem(slot,item(t.kind().icon,t.name()+" - "+t.kind().label,"Waiting: "+minutes+" minute(s)","Claim: "+claim,"Requester: "+(Bukkit.getPlayer(t.owner())==null?"Offline":"Online"),t.target().isEmpty()?"":"Target: "+t.target(),"Click to manage"));
        }
        if(tickets.isEmpty())menu.inventory.setItem(22,item(Material.GRAY_DYE,"No pending requests"));
        if(page>0)menu.inventory.setItem(45,item(Material.ARROW,"Previous"));
        menu.inventory.setItem(49,item(Material.SUNFLOWER,"Refresh"));
        if((page+1)*45<tickets.size())menu.inventory.setItem(53,item(Material.ARROW,"Next"));
        player.openInventory(menu.inventory);
    }
    static void detail(GEP p,Player player,UUID id,int page){
        check(p,player);var t=p.requests.pending(id);
        var menu=new RequestDashboard(p,player.getUniqueId(),id,page);
        menu.inventory=Bukkit.createInventory(menu,27,Component.text("Manage request"));
        menu.inventory.setItem(4,item(t.kind().icon,t.name()+" - "+t.kind().label,"Target: "+t.target()));
        if(player.hasPermission("gep.requests.visit."+t.kind().key()))menu.inventory.setItem(10,item(Material.ENDER_PEARL,"Visit requester","Successful visit claims this request."));
        if(t.kind()==Requests.Kind.KILL&&player.hasPermission("gep.requests.decide")){
            menu.inventory.setItem(12,item(Material.LIME_DYE,"Approve"));menu.inventory.setItem(14,item(Material.RED_DYE,"Deny"));
        }else if(t.kind()!=Requests.Kind.KILL&&player.hasPermission("gep.requests.close"))menu.inventory.setItem(12,item(Material.LIME_DYE,"Close request"));
        if(!t.claim().isEmpty()&&player.hasPermission("gep.requests.release"))menu.inventory.setItem(16,item(Material.NAME_TAG,"Release claim"));
        menu.inventory.setItem(22,item(Material.ARROW,"Back"));player.openInventory(menu.inventory);
    }
    @EventHandler(priority=EventPriority.HIGHEST) void click(InventoryClickEvent e){
        if(!(e.getView().getTopInventory().getHolder() instanceof RequestDashboard menu))return;
        e.setCancelled(true);if(!(e.getWhoClicked() instanceof Player player)||!player.getUniqueId().equals(menu.owner))return;
        int slot=e.getRawSlot();if(slot<0||slot>=menu.inventory.getSize()||menu.inventory.getItem(slot)==null)return;
        p.getServer().getScheduler().runTask(p,()->{
            if(!player.isOnline()||!player.getOpenInventory().getTopInventory().equals(menu.inventory))return;
            try{
                check(p,player);if(p.rules.noInteract(player))return;
                if(menu.ticket==null){
                    if(menu.entries.containsKey(slot))detail(p,player,menu.entries.get(slot),menu.page);
                    else open(p,player,menu.page+(slot==45?-1:slot==53?1:0));
                }else{
                    if(slot==22){open(p,player,menu.page);return;}
                    if(slot==10){p.requests.visit(player,menu.ticket);player.closeInventory();return;}
                    if(slot==12)p.requests.decide(player,menu.ticket,p.requests.pending(menu.ticket).kind()==Requests.Kind.KILL?"APPROVED":"CLOSED");
                    else if(slot==14)p.requests.decide(player,menu.ticket,"DENIED");
                    else if(slot==16)p.requests.release(player,menu.ticket);
                    else return;
                    open(p,player,menu.page);
                }
            }catch(IllegalArgumentException ex){GEP.tell(player,ex.getMessage());player.closeInventory();}
        });
    }
    @EventHandler void drag(InventoryDragEvent e){if(e.getView().getTopInventory().getHolder() instanceof RequestDashboard)e.setCancelled(true);}
}
