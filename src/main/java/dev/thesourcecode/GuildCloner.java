package dev.thesourcecode;

import net.dv8tion.jda.api.AccountType;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;

import javax.security.auth.login.LoginException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class GuildCloner {
    private final String deleteFormat = "Attempting deletion: %s; dry: %s";
    private final String copyFormat = "Attempting clone: %s; dry: %s";
    private final Member targetSelfMember;
    private final Guild source, target;
    private final boolean dry;

    private GuildCloner(JDA jda, Guild source, Guild target, boolean dry){
        final User selfUser = jda.getSelfUser();
        this.targetSelfMember = target.getMember(selfUser);
        this.source = source;
        this.target = target;
        this.dry = dry;
    }

    public static void main(String[] args) throws LoginException, InterruptedException {
        if (args.length == 0){
            throw new IllegalArgumentException("You must provide a bot token!");
        }
        final String token = args[0];
        final JDA jda = new JDABuilder(AccountType.BOT).setToken(token).build();
        jda.awaitReady();
        if (args.length < 3){
            throw new IllegalArgumentException("You must provide 2 guild IDs, with the source ID being first and target second!");
        }
        final String sourceID = args[1];
        final String targetId = args[2];
        final Guild
            source = jda.getGuildById(sourceID),
            target = jda.getGuildById(targetId);
        if (source == null || target == null){
            throw new IllegalArgumentException("I must be in both the source and target guild!");
        }
        boolean dry = false;
        if (args.length == 4){
            dry = true;
        }
        new GuildCloner(jda, source, target, dry).startClone();
    }

    private void startClone() {
        emptyTarget();
        cloneSource();
    }

    private void emptyTarget(){
        System.out.println("Emptying: " + target);
        final List<Role> roles = target.getRoles();
        final List<GuildChannel> channels = target.getChannels();
        final List<Emote> emotes = target.getEmotes();
        roles.forEach(it -> {
            System.out.println(String.format(deleteFormat, it, dry));
            if (it.isManaged()) return;
            if (!targetSelfMember.canInteract(it)) return;
            if (dry) return;
            it.delete().queue($ -> {}, $ -> {});
        });
        channels.forEach(it -> {
            System.out.println(String.format(deleteFormat, it, dry));
            if (dry) return;
            it.delete().complete();
        });
        emotes.forEach(it -> {
            System.out.println(String.format(deleteFormat, it, dry));
            if (!it.canInteract(targetSelfMember)) return;
            if (dry) return;
            it.delete().complete();
        });
    }

    private void cloneSource() {
        System.out.println("Cloning: " + source);
        final Map<Role, Role> roleMappings = new HashMap<>();
        final Map<GuildChannel, GuildChannel> channelMappings = new HashMap<>();
        final List<Role> roles = source.getRoles();
        roles.forEach(it -> {
            System.out.println(String.format(copyFormat, it, dry));
            if (dry) return;
            if (it.isManaged()) return;
            if (it.isPublicRole()) return;
            if (it == source.getPublicRole()){
                roleMappings.put(it, target.getPublicRole());
                return;
            }
            final long rawPerms = it.getPermissionsRaw();
            final Role newRole = target.createRole()
                .setColor(it.getColor())
                .setHoisted(it.isHoisted())
                .setMentionable(it.isMentionable())
                .setName(it.getName())
                .setPermissions(rawPerms >= Permission.ALL_PERMISSIONS ? Permission.ALL_PERMISSIONS : rawPerms).complete();
            roleMappings.put(it, newRole);
        });
        source.getMembers().forEach(it -> {
            if (dry) return;
            final Member other = target.getMember(it.getUser());
            if (other == null) return;
            it.getRoles().stream().map(roleMappings::get)
                .filter(Objects::nonNull).forEach(role -> target.addRoleToMember(other, role).queue());
        });
        final List<GuildChannel> channels = source.getChannels();
        channels.forEach(it -> {
            System.out.println(String.format(copyFormat, it, dry));
            if (dry) return;
            final GuildChannel newChannel = it.createCopy(target).complete();
            channelMappings.put(it, newChannel);
            it.getMemberPermissionOverrides().forEach(override -> {
                final Member member = override.getMember();
                if (member == null) return;
                final String memberID = member.getId();
                final Member other = target.getMemberById(memberID);
                if (other == null) return;
                final GuildChannel channel = channelMappings.get(it);
                if (channel == null) return;
                channel.putPermissionOverride(other).setAllow(override.getAllowed()).setDeny(override.getDenied()).complete();
            });
            it.getRolePermissionOverrides().forEach(override -> {
                final Role role = override.getRole();
                if (role == null) return;
                final Role other = roleMappings.get(role);
                if (other == null) return;
                final GuildChannel otherChannel = channelMappings.get(it);
                if (otherChannel == null) return;
                otherChannel.putPermissionOverride(other).setAllow(override.getAllowed()).setDeny(override.getDenied()).complete();
            });
        });
        channels.forEach(it -> {
            if (it instanceof Category){
                final Category newParent = (Category) channelMappings.get(it);
                if (newParent == null) return;
                final List<GuildChannel> children = ((Category) it).getChannels();
                if (children.isEmpty()) return;
                children.forEach(child -> {
                    final GuildChannel other = channelMappings.get(child);
                    if (other == null) return;
                    other.getManager().setParent(newParent).complete();
                });
            }
        });
    }

}
