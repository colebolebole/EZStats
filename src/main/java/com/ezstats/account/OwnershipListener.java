package com.ezstats.account;

import com.ezstats.snapshot.SnapshotService;
import com.github.blade.hvac.api.event.DeviceRemovedEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/** A device that is gone has no owner: a new one built on the same block must not inherit it. */
public final class OwnershipListener implements Listener {
    private final AccountStore accounts;

    public OwnershipListener(AccountStore accounts) {
        this.accounts = accounts;
    }

    @EventHandler
    public void onRemoved(DeviceRemovedEvent event) {
        accounts.release(SnapshotService.key(event.getDevice().position()));
    }
}
