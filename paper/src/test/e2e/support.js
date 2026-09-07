import { expect } from '@drownek/plugwright';

export async function invite(player, opponent, loadout = 'Own inventory') {
  const sent = player.messageBuffer.length;
  const received = opponent.messageBuffer.length;
  player.chat(`/duel ${opponent.username}`);
  const objectives = await player.gui({ title: /Objective/ });
  await objectives.locator(i => i.getDisplayName().includes('Elimination')).click();
  const loadouts = await player.gui({ title: /Loadout/ });
  await loadouts.locator(i => i.getDisplayName().includes(loadout)).click();
  const rules = await player.gui({ title: /Rules/ });
  await rules.locator(i => i.getDisplayName().includes('Send challenge')).click();
  await expect(player).toHaveReceivedMessage(`Challenge sent to ${opponent.username}`, { since: sent });
  await expect(opponent).toHaveReceivedMessage(`${player.username} offers you a duel`, { since: received });
}
