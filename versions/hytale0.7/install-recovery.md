---
title: "Install, update and remove"
---

# Install, update and remove {#install-update-and-remove}

This guide follows the **Hytale 0.7 support line**. Published files can lag behind branch changes; read the chosen file’s release notes for the features it includes.

## Install {#install}

This guide covers Kweebet for **Hytale 0.7 / Java 25**. Choose a compatible file from [GitHub releases](https://github.com/jkswoods/Kweebet/releases), checking its exact Hytale patch requirements and release notes.

1. Disconnect from the world and close Hytale before changing installed mods.
2. Put the JAR in Hytale's `UserData/Mods` directory.
3. Keep only one Kweebet version installed.
4. Enable **Kweebet** in the world's mod settings, then load the world.

A dedicated server installs the same JAR in its server mods directory. Multiplayer and provider compatibility are still preview features; back up before server use. No external economy or payment service is required.

## Save and update {#save-and-update}

Back up the complete world, its player files and the matching mod JAR together. Finish active games before updating where possible. Install the new version with the server stopped and preserve your backup until you have checked the world.

Saved outcomes, accepted terms, hands, paths and owed rewards survive normal reload. Closing a page does not change a committed outcome. An expired Blackjack turn stands. World display pieces are rebuilt from saved state and do not hold item balances themselves.

If your bag is full, an owed reward stays owned at its machine. Free space, then reopen or Collect. A collected reward cannot be paid again. Losing bets do not become recoverable stock.

Earlier development-save item and resource identities are preserved for compatibility. Do not downgrade a newer world, restore only one player's inventory, or combine files from different backups. Normal saving is supported; power-loss, partial rollback and arbitrary inventory-provider recovery are not certified.

## Remove a machine {#remove-a-machine}

Finish its round and collect every owed reward. Only the placing owner can remove an empty machine. Removal is refused while protected funds or obligations remain. Do not use world editing to bypass those protections.

To uninstall, finish and empty the machines, remove them normally, save and close the world, then remove the JAR. Keep a full backup if any protected legacy balance remains.
