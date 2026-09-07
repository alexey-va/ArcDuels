import { test, expect } from '@drownek/plugwright';

import { invite } from './support.js';

test('GUI invitation reaches the opponent; decline clears both pending challenges', async ({ player, createPlayer }) => {
  const opponent = await createPlayer({ username: 'DuelDecliner' });
  await invite(player, opponent);
  opponent.chat('/duel deny');
  await expect(player).toHaveReceivedMessage('The duel challenge was declined.');
  await expect(opponent).toHaveReceivedMessage('The duel challenge was declined.');
  opponent.chat('/duel deny');
  await expect(opponent).toHaveReceivedMessage('You have no incoming challenges.');
  player.chat('/duel cancel');
  await expect(player).toHaveReceivedMessage('You have no outgoing challenges.');
});

test('sender cancels a GUI invitation and can immediately send a fresh one', async ({ player, createPlayer }) => {
  const opponent = await createPlayer({ username: 'DuelReceiver' });
  await invite(player, opponent);
  player.chat('/duel cancel');
  await expect(player).toHaveReceivedMessage('The duel challenge was cancelled.');
  await expect(opponent).toHaveReceivedMessage('The duel challenge was cancelled.');
  await invite(player, opponent);
  opponent.chat('/duel deny');
  await expect(player).toHaveReceivedMessage('The duel challenge was declined.');
});

test('expired GUI invitation releases both pending challenge slots', async ({ player, createPlayer }) => {
  const opponent = await createPlayer({ username: 'DuelExpiry' });
  await invite(player, opponent);

  await expect(player).toHaveReceivedMessage(
    'Your duel challenge with DuelExpiry expired.',
    { timeout: 15000 },
  );
  await expect(opponent).toHaveReceivedMessage(
    `Your duel challenge with ${player.username} expired.`,
    { timeout: 1000 },
  );

  opponent.chat('/duel deny');
  await expect(opponent).toHaveReceivedMessage('You have no incoming challenges.');

  await invite(player, opponent);
  opponent.chat('/duel deny');
  await expect(player).toHaveReceivedMessage('The duel challenge was declined.');
});
