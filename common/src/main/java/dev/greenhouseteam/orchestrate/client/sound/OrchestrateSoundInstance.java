package dev.greenhouseteam.orchestrate.client.sound;

import dev.greenhouseteam.mib.client.sound.MibSoundInstance;
import dev.greenhouseteam.mib.data.ExtendedSound;
import dev.greenhouseteam.mib.data.KeyWithOctave;
import dev.greenhouseteam.mib.data.MibSoundSet;
import dev.greenhouseteam.mib.mixin.client.SoundEngineAccessor;
import dev.greenhouseteam.mib.mixin.client.SoundManagerAccessor;
import dev.greenhouseteam.orchestrate.registry.OrchestrateSoundEvents;
import dev.greenhouseteam.orchestrate.song.Note;
import dev.greenhouseteam.orchestrate.song.Song;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public class OrchestrateSoundInstance extends MibSoundInstance {
    @Nullable
    private final OrchestrateSoundInstance rootSound;
    private final List<OrchestrateSoundInstance> children = new ArrayList<>();

    private final Song song;
    private final MibSoundSet soundSet;
    private final int duration;
    private int elapsedTicks;

    protected OrchestrateSoundInstance(@Nullable OrchestrateSoundInstance rootSound,
                                       double x, double y, double z, SoundEvent sound,
                                       Song song, MibSoundSet soundSet,
                                       ExtendedSound extendedSound, SoundSource source,
                                       float volume, float pitch, int duration, int elapsedDuration,
                                       boolean isLooping) {
        this(rootSound, null, x, y, z, p -> true, sound, song, soundSet, extendedSound, source, volume, pitch, duration, elapsedDuration, isLooping);
    }

    protected OrchestrateSoundInstance(@Nullable OrchestrateSoundInstance masterSound,
                                       Player player, ItemStack stack, SoundEvent sound, Song song,
                                       MibSoundSet soundSet, ExtendedSound extendedSound, SoundSource source,
                                       float volume, float pitch, int duration, int elapsedDuration,
                                       boolean isLooping) {
        this(masterSound, player, player.getX(), player.getY(), player.getZ(), p -> !p.isUsingItem() || p.getUseItem() != stack, sound, song, soundSet, extendedSound, source, volume, pitch, duration, elapsedDuration, isLooping);
    }

    protected OrchestrateSoundInstance(@Nullable OrchestrateSoundInstance rootSound,
                                       @Nullable Player player,
                                       double x, double y, double z,
                                       Predicate<Player> stopPredicate,
                                       SoundEvent sound,
                                       Song song, MibSoundSet soundSet, ExtendedSound extendedSound, SoundSource source,
                                       float volume, float pitch, int duration, int elapsedDuration,
                                       boolean isLooping) {
        super(player, x, y, z, stopPredicate, sound, extendedSound, source, volume, pitch, isLooping);
        this.rootSound = rootSound;
        if (rootSound != null)
            rootSound.children.add(this);
        this.song = song;
        this.soundSet = soundSet;
        this.duration = duration;
        this.elapsedTicks = elapsedDuration;
    }

    // TODO: Use full song class when that's created
    public static OrchestrateSoundInstance createPlayerDependentMaster(Player player, ItemStack stack,
                                                                       Song song, MibSoundSet soundSet, SoundSource source) {
        // Any sound event being passed here will work, I just chose armadillo because it was funny.
        return new OrchestrateSoundInstance(null,
                player, stack, OrchestrateSoundEvents.MASTER,
                song, soundSet, soundSet.getSound(KeyWithOctave.DEFAULT, 1.0F), source,
                1.0F, 1.0F, song.duration(), 0, false);
    }

    public boolean isMaster() {
        return rootSound == null;
    }

    @Override
    public void tick() {
        if (isStopped())
            return;

        replaceWithLoopSoundIfNecessary();

        if (handleMasterStop())
            return;
        if (handleStop())
            return;

        playNewNotes();

        if (player != null) {
            this.x = player.getX();
            this.y = player.getY();
            this.z = player.getZ();
        }

        ++elapsedTicks;
    }

    public void playNewNotes() {
        if (isMaster()) {
            for (Note note : song.notes().stream().filter(note -> note.startTime() == elapsedTicks).toList()) {
                ExtendedSound sound = soundSet.getSound(note.key(), volume);
                float pitch = note.key().getPitchFromNote();
                Minecraft.getInstance().getSoundManager().queueTickingSound(new OrchestrateSoundInstance(this, player, x, y, z, stopPredicate, sound.sounds().start().value(), song, soundSet, extendedSound, source, note.volume(), pitch, 30, 0, false));
            }
        }
    }

    public void replaceWithLoopSoundIfNecessary() {
        SoundEngineAccessor soundEngine = ((SoundEngineAccessor) ((SoundManagerAccessor)Minecraft.getInstance().getSoundManager()).mib$getSoundEngine());
        if (!isMaster() && !hasPlayedLoop && getOrCalculateStartSoundStop() <= soundEngine.mib$getTickCount() && extendedSound.sounds().loop().isPresent()) {
            hasPlayedLoop = true;
            shouldPlayStopSound = false;
            elapsedTicks = 0;
            stop();
            ((SoundEngineAccessor)((SoundManagerAccessor)Minecraft.getInstance().getSoundManager()).mib$getSoundEngine()).mib$getInstanceToChannel().remove(this);
            Minecraft.getInstance().getSoundManager().queueTickingSound(new OrchestrateSoundInstance(this.rootSound, player, x, y, z, stopPredicate, extendedSound.sounds().loop().orElse(extendedSound.sounds().start()).value(), song, soundSet, extendedSound, source, volume, pitch, duration, elapsedTicks, true));
        }
    }

    public boolean handleStop() {
        SoundEngine soundEngine = ((SoundManagerAccessor)Minecraft.getInstance().getSoundManager()).mib$getSoundEngine();
        SoundEngineAccessor accessor = (SoundEngineAccessor) soundEngine;
        if (!isMaster() && (elapsedTicks > duration || (accessor.mib$getSoundDeleteTime().get(this) - 1 <= accessor.mib$getTickCount() || player != null && stopPredicate.test(player)))) {
            if (shouldPlayStopSound && extendedSound.sounds().stop().isPresent())
                Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(extendedSound.sounds().stop().get().value(), this.volume, this.pitch));
            rootSound.children.remove(this);
            stop();
            elapsedTicks = 0;
            return true;
        }
        return false;
    }

    public boolean handleMasterStop() {
        if (isMaster() && (elapsedTicks > duration || player != null && stopPredicate.test(player))) {
            stopMaster();
            return true;
        }
        return false;
    }

    public void stopMaster() {
        for (OrchestrateSoundInstance child : children) {
            child.children.remove(this);
            child.stop();
        }
        stop();
        elapsedTicks = 0;
    }

    @Override
    public boolean canPlaySound() {
        return (elapsedTicks <= duration || player == null || !stopPredicate.test(player));
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }
}
