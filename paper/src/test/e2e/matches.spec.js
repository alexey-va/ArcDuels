import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import chat from 'prismarine-chat';
import { test, expect, waitUntil } from '@drownek/plugwright';
import { invite } from './support.js';

function count(player, name) {
  return player.bot.inventory.items().filter(item => item.name === name).reduce((n, item) => n + item.count, 0);
}

function inventory(player) {
  return JSON.stringify(player.bot.inventory.slots.slice(5, 46).map(item => item && {
    name: item.name, count: item.count, nbt: item.nbt, components: item.components,
  }));
}

function titles(player) {
  const received = [];
  const ChatMessage = chat(player.bot.registry);
  player.bot._client.on('set_title_text', packet => received.push(ChatMessage.fromNotch(packet.text).toString()));
  return received;
}

async function seed(player, server, x, marker, signal) {
  await player.teleport(x, -60, 0.5);
  await player.giveItem(marker, 3);
  await player.giveItem('iron_helmet', 1);
  await player.bot.equip(player.bot.inventory.items().find(item => item.name === 'iron_helmet'), 'head');
  await player.giveItem('torch', 5);
  await player.bot.equip(player.bot.inventory.items().find(item => item.name === 'torch'), 'off-hand');
  const sync = `seed-${randomUUID()}`;
  server.execute(`minecraft:experience set ${player.username} 7 levels`);
  server.execute(`minecraft:tellraw ${player.username} {"text":"${sync}"}`);
  await expect(player).toHaveReceivedMessage(sync);
  await waitUntil(() => count(player, marker) === 3 && player.bot.experience.level === 7, { signal });
  return { inventory: inventory(player), x, marker };
}

async function start(player, opponent, loadout, signal) {
  const firstTitles = titles(player);
  const secondTitles = titles(opponent);
  await invite(player, opponent, loadout);
  opponent.chat('/duel accept');
  await waitUntil(() => firstTitles.includes('Fight') && secondTitles.includes('Fight'), {
    timeout: 20000, signal, message: 'Both players must enter an active, durable duel',
  });
  assert.ok(Math.abs(player.bot.entity.position.x - 0.5) < 0.5);
  assert.ok(Math.abs(opponent.bot.entity.position.x - 0.5) < 0.5);
  return { firstTitles, secondTitles };
}

async function restored(player, before, signal, exactInventory = true) {
  await waitUntil(() => Math.abs(player.bot.entity.position.x - before.x) < 0.5 &&
    Math.abs(player.bot.entity.position.y + 60) < 0.2 &&
    player.bot.experience.level === 7 &&
    (!exactInventory || inventory(player) === before.inventory), {
    timeout: 15000, signal, message: `${player.username}: original location, XP and inventory must be restored`,
  });
  assert.equal(count(player, before.marker), 3);
}

test('classic kit melee victory restores both exact inventories and releases the arena', async ({ player, createPlayer, server, signal }) => {
  const opponent = await createPlayer({ username: 'KitOpponent' });
  const first = await seed(player, server, 40.5, 'diamond', signal);
  const second = await seed(opponent, server, 44.5, 'emerald', signal);
  const outcome = await start(player, opponent, 'Classic', signal);
  for (const [fighter, marker] of [[player, 'diamond'], [opponent, 'emerald']]) {
    assert.equal(count(fighter, marker), 0);
    assert.equal(count(fighter, 'diamond_sword'), 1);
    assert.equal(count(fighter, 'golden_apple'), 4);
    assert.equal(fighter.bot.inventory.slots[5]?.name, 'diamond_helmet');
  }
  await player.bot.equip(player.bot.inventory.items().find(item => item.name === 'diamond_sword'), 'hand');
  let nextAttack = 0;
  let sawDamage = false;
  try {
    await waitUntil(async () => {
      if (outcome.firstTitles.includes('Victory')) return true;
      const target = player.bot.players[opponent.username]?.entity;
      if (!target) return false;
      sawDamage ||= opponent.bot.health < 20;
      await player.bot.lookAt(target.position.offset(0, 1.4, 0), true);
      const distance = player.bot.entity.position.distanceTo(target.position);
      player.bot.setControlState('forward', distance > 2.2);
      if (distance < 3 && performance.now() >= nextAttack) {
        player.bot.attack(target);
        nextAttack = performance.now() + 650;
      }
      return false;
    }, { timeout: 45000, interval: 50, signal, message: 'Real melee attacks must finish the elimination match' });
  } finally {
    player.bot.clearControlStates();
  }
  assert.ok(sawDamage, 'The opponent must take real combat damage');
  await waitUntil(() => outcome.secondTitles.includes('Defeat'), { signal });
  await restored(player, first, signal);
  await restored(opponent, second, signal);
  await expect(player).toHaveReceivedMessage(`Victory over ${opponent.username}`);
  await expect(opponent).toHaveReceivedMessage(`Defeat to ${player.username}`);
});

test('own-inventory forfeit preserves consumed items and returns both players safely', async ({ player, createPlayer, server, signal }) => {
  const opponent = await createPlayer({ username: 'OwnOpponent' });
  const first = await seed(player, server, 40.5, 'diamond', signal);
  const second = await seed(opponent, server, 44.5, 'emerald', signal);
  await player.giveItem('golden_apple', 2);
  const outcome = await start(player, opponent, 'Own inventory', signal);
  assert.equal(count(player, 'diamond'), 3);
  assert.equal(count(player, 'golden_apple'), 2);
  assert.equal(count(opponent, 'emerald'), 3);
  await player.bot.equip(player.bot.inventory.items().find(item => item.name === 'golden_apple'), 'hand');
  await player.bot.consume();
  await waitUntil(() => count(player, 'golden_apple') === 1, { signal });
  const afterConsumption = inventory(player);
  player.chat('/duel leave');
  await expect(player).toHaveReceivedMessage('You forfeited. Saving the result');
  await waitUntil(() => outcome.secondTitles.includes('Victory'), { signal });
  await restored(player, first, signal, false);
  await restored(opponent, second, signal);
  assert.equal(inventory(player), afterConsumption, 'Recovery must not refund the consumed apple');
  assert.equal(count(player, 'golden_apple'), 1);
  await expect(opponent).toHaveReceivedMessage(`Victory over ${player.username}`);
});

test('disconnect during a kit fight restores the player on reconnect without keeping the kit', async ({ player, createPlayer, server, signal }) => {
  const opponent = await createPlayer({ username: 'QuitOpponent' });
  const first = await seed(player, server, 40.5, 'diamond', signal);
  const second = await seed(opponent, server, 44.5, 'emerald', signal);
  const outcome = await start(player, opponent, 'Classic', signal);
  assert.equal(count(opponent, 'emerald'), 0);
  assert.equal(count(opponent, 'diamond_sword'), 1);
  await opponent.rejoin();
  await waitUntil(() => outcome.firstTitles.includes('Victory'), { signal });
  await restored(player, first, signal);
  await restored(opponent, second, signal);
  await expect(player).toHaveReceivedMessage(`Victory over ${opponent.username}`);
  // A second reconnect must not replay the archived snapshot or duplicate items.
  await opponent.rejoin();
  await restored(opponent, second, signal);
  assert.equal(count(opponent, 'diamond_sword'), 0);
});
