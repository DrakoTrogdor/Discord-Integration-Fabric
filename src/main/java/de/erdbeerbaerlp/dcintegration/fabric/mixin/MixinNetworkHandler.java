package de.erdbeerbaerlp.dcintegration.fabric.mixin;


import dcshadow.net.kyori.adventure.text.Component;
import dcshadow.net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import de.erdbeerbaerlp.dcintegration.common.storage.Configuration;
import de.erdbeerbaerlp.dcintegration.common.storage.PlayerLinkController;
import de.erdbeerbaerlp.dcintegration.common.util.DiscordMessage;
import de.erdbeerbaerlp.dcintegration.common.util.MessageUtils;
import de.erdbeerbaerlp.dcintegration.fabric.DiscordIntegration;
import de.erdbeerbaerlp.dcintegration.fabric.api.FabricDiscordEventHandler;
import de.erdbeerbaerlp.dcintegration.fabric.util.FabricMessageUtils;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.TextChannel;
//import net.minecraft.network.MessageType;
import net.minecraft.network.message.MessageDecorator;
import net.minecraft.network.message.MessageSignature;
import net.minecraft.network.message.MessageType; //changed from 1.18
import net.minecraft.network.message.SignedMessage;
import net.minecraft.server.filter.FilteredMessage;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
//import net.minecraft.text.TranslatableText;
import net.minecraft.text.TranslatableTextContent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static de.erdbeerbaerlp.dcintegration.common.util.Variables.discord_instance;

@Mixin(ServerPlayNetworkHandler.class)
public class MixinNetworkHandler {
    @Shadow
    public ServerPlayerEntity player;

    /**
     * Handle chat messages
     */
    @Redirect(method = "handleMessage", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/message/MessageDecorator;decorateChat(Lnet/minecraft/server/network/ServerPlayerEntity;Lnet/minecraft/server/filter/FilteredMessage;Lnet/minecraft/network/message/MessageSignature;Z)Ljava/util/concurrent/CompletableFuture;"))
    public CompletableFuture<FilteredMessage<SignedMessage>> chatMessage(MessageDecorator instance, ServerPlayerEntity sender, FilteredMessage<Text> message, MessageSignature signature, boolean previewed) {
        if (discord_instance == null) return instance.decorateChat(sender, message, signature, previewed);

        if (PlayerLinkController.getSettings(null, player.getUuid()).hideFromDiscord) {
            return instance.decorateChat(sender, message, signature, previewed);
        }

        Text finalTxt = message.raw(); /// not sure which one raw or filtered
        if (discord_instance.callEvent((e) -> {
            if (e instanceof FabricDiscordEventHandler) {
                return ((FabricDiscordEventHandler) e).onMcChatMessage(finalTxt, player);
            }
            return false;
        })) {
            return instance.decorateChat(sender, message, signature, previewed);
        }
        String text = MessageUtils.escapeMarkdown(((String) ((TranslatableTextContent) message.raw()).getArgs()[1]));
        final MessageEmbed embed = FabricMessageUtils.genItemStackEmbedIfAvailable(message.raw());
        if (discord_instance != null) {
            TextChannel channel = discord_instance.getChannel(Configuration.instance().advanced.chatOutputChannelID);
            if (channel == null) {
                return instance.decorateChat(sender, message, signature, previewed);
            }
            discord_instance.sendMessage(FabricMessageUtils.formatPlayerName(player), player.getUuid().toString(), new DiscordMessage(embed, text, true), channel);
            final String json = Text.Serializer.toJson(message.raw());
            Component comp = GsonComponentSerializer.gson().deserialize(json);
            final String editedJson = GsonComponentSerializer.gson().serialize(MessageUtils.mentionsToNames(comp, channel.getGuild()));

            //txt = Text.Serializer.fromJson(editedJson);
        }
        return instance.decorateChat(sender, message, signature, previewed);
    }

    /**
     * Handle possible timeout
     */
    @Inject(method = "disconnect", at = @At("HEAD"))
    private void onDisconnect(final Text textComponent, CallbackInfo ci) {
        if (textComponent.equals(Text.translatable("disconnect.timeout")))
            DiscordIntegration.timeouts.add(this.player.getUuid());
    }
}
