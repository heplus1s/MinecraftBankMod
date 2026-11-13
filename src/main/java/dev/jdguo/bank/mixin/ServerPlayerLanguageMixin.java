package dev.jdguo.bank.mixin;

import dev.jdguo.bank.text.BankLocale;
import net.minecraft.network.packet.c2s.play.ClientSettingsC2SPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayerEntity.class)
public class ServerPlayerLanguageMixin {
    @Inject(method = "setClientSettings", at = @At("TAIL"))
    private void bankmod$rememberLanguage(ClientSettingsC2SPacket packet, CallbackInfo ci) {
        BankLocale.remember(((ServerPlayerEntity) (Object) this).getUuid(), packet.language());
    }
}
