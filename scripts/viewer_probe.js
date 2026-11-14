const mineflayer = require('mineflayer');
const fs = require('fs');

const directory = 'server';
const bot = mineflayer.createBot({
  host: '127.0.0.1',
  port: 25566,
  username: 'SBotViewer',
  auth: 'offline',
  version: '1.20.1',
  hideErrors: false
});

let spawned = false;
bot.on('spawn', () => {
  spawned = true;
  fs.writeFileSync(directory + '/viewer.ready', 'ready');
  console.log('VIEWER: Minecraft 1.20.1 client joined and received world');
});
bot.on('entitySpawn', (entity) => {
  if (entity.type === 'player') {
    console.log('VIEWER entitySpawn: ' + (entity.username || '?') + ' id=' + entity.id);
  }
});
bot.on('error', error => { console.error('VIEWER ERROR: ' + error.stack); process.exitCode = 1; });
bot.on('kicked', reason => { console.error('VIEWER KICK: ' + JSON.stringify(reason)); process.exitCode = 1; });
bot.on('end', () => {
  if (process.exitCode) process.exit(process.exitCode);
  console.log('VIEWER disconnected');
});

setInterval(() => {
  if (!spawned) return;
  let target;
  try {
    target = JSON.parse(fs.readFileSync(directory + '/viewer.target.json', 'utf8'));
  } catch { return; }

  // Execute requested /sbot tp even if the old entity was despawned
  // when the viewer moved away from its first location.
  try {
    const cmd = JSON.parse(fs.readFileSync(directory + '/viewer.command.json', 'utf8'));
    const marker = directory + '/viewer.command.done';
    if (!fs.existsSync(marker)) {
      fs.writeFileSync(marker, cmd.command);
      bot.chat(cmd.command);
      console.log('VIEWER dispatched ' + cmd.command);
    }
  } catch {}

  const playerInfo = bot.players[target.name];
  const entity = playerInfo && playerInfo.entity ||
      Object.values(bot.entities).find(entity => entity.type === 'player' && entity.username === target.name);
  if (!entity || !entity.position) return;

  const position = entity.position;
  let correct = false;
  if (target.stage === 1) {
    correct = Math.abs(position.x - target.x) < 1.0 &&
              Math.abs(position.y - target.y) < 1.0 &&
              Math.abs(position.z - target.z) < 1.0;
  } else {
    const viewer = bot.entity.position;
    const distance = position.distanceTo(viewer);
    correct = distance < 4.5 && Math.abs(position.x - target.oldX) > 8;
  }
  if (correct) {
    const result = {stage: target.stage, name: target.name,
      x: position.x, y: position.y, z: position.z};
    fs.writeFileSync(directory + '/viewer.seen.json', JSON.stringify(result));
  }


}, 250);
