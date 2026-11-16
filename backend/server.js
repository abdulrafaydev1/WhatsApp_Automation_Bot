'use strict';

const express = require('express');
const cors = require('cors');
const multer = require('multer');
const pino = require('pino');
const QRCode = require('qrcode');
const qrcodeTerminal = require('qrcode-terminal');
const fs = require('fs');
const path = require('path');
const {
  default: makeWASocket,
  useMultiFileAuthState,
  DisconnectReason,
  fetchLatestBaileysVersion,
  Browsers
} = require('@whiskeysockets/baileys');

const PORT = process.env.PORT || 3000;
const AUTH_DIR = path.join(__dirname, 'auth_info_baileys');
const PROCESSED_TASKS_FILE = path.join(__dirname, 'processed_tasks.json');
const AUTO_RULES_FILE = path.join(__dirname, 'auto_rules.json');
const LAST_SENT_KEYS_FILE = path.join(__dirname, 'last_sent_keys.json');
const AUTO_MEDIA_DIR = path.join(__dirname, 'auto_rule_media');

if (!fs.existsSync(AUTO_MEDIA_DIR)) {
  fs.mkdirSync(AUTO_MEDIA_DIR, { recursive: true });
}

const app = express();
app.use(cors());
app.use(express.json({ limit: '50mb' }));
app.use(express.urlencoded({ extended: true, limit: '50mb' }));

// Configure Multer with memory storage for direct Buffer upload to Baileys
const upload = multer({
  storage: multer.memoryStorage(),
  limits: {
    fileSize: 50 * 1024 * 1024 // 50 MB max image/media payload
  }
});

// Logger setup
const logger = pino({ level: process.env.LOG_LEVEL || 'info' });
const baileysLogger = pino({ level: 'silent' });

// Idempotency tracking: In-memory Set backed by disk persistence so restarts don't resend
const processedTaskIds = new Set();
const inFlightTaskIds = new Set();

// Cooldown & Anti-Self-Loop tracking for 24/7 Baileys Auto-Responder Rules
const autoRulesMap = new Map(); // ruleId -> rule object
const autoCooldownMap = new Map(); // `${ruleId}_${remoteJid}` -> timestampMs
const pendingGroupTimers = new Set(); // remoteJid -> active 5s delay lock
const lastSentTimestamps = new Map(); // remoteJid -> timestampMs
const recentBotGroupSends = new Map(); // remoteJid -> timestampMs
const lastSentMessageKeys = new Map(); // groupJid (@g.us) -> Baileys msg.key ({ remoteJid, fromMe, id })

function loadProcessedTasks() {
  try {
    if (fs.existsSync(PROCESSED_TASKS_FILE)) {
      const raw = fs.readFileSync(PROCESSED_TASKS_FILE, 'utf-8');
      const list = JSON.parse(raw);
      if (Array.isArray(list)) {
        list.forEach((id) => processedTaskIds.add(id));
      }
    }
  } catch (err) {
    logger.warn({ err }, 'Could not load processed_tasks.json, starting fresh set.');
  }
}

function saveProcessedTask(taskId) {
  processedTaskIds.add(taskId);
  try {
    const serialized = JSON.stringify(Array.from(processedTaskIds).slice(-5000), null, 2);
    fs.writeFileSync(PROCESSED_TASKS_FILE, serialized, 'utf-8');
  } catch (err) {
    logger.warn({ err }, 'Could not persist processed_tasks.json');
  }
}

function loadAutoRules() {
  try {
    if (fs.existsSync(AUTO_RULES_FILE)) {
      const raw = fs.readFileSync(AUTO_RULES_FILE, 'utf-8');
      const list = JSON.parse(raw);
      if (Array.isArray(list)) {
        list.forEach((rule) => {
          if (rule && rule.ruleId) {
            autoRulesMap.set(rule.ruleId, rule);
          }
        });
      }
    }
  } catch (err) {
    logger.warn({ err }, 'Could not load auto_rules.json, starting with empty rules.');
  }
}

function saveAutoRules() {
  try {
    const serialized = JSON.stringify(Array.from(autoRulesMap.values()), null, 2);
    fs.writeFileSync(AUTO_RULES_FILE, serialized, 'utf-8');
  } catch (err) {
    logger.warn({ err }, 'Could not persist auto_rules.json');
  }
}

function loadLastSentKeys() {
  try {
    if (fs.existsSync(LAST_SENT_KEYS_FILE)) {
      const raw = fs.readFileSync(LAST_SENT_KEYS_FILE, 'utf-8');
      const obj = JSON.parse(raw);
      if (obj && typeof obj === 'object') {
        Object.entries(obj).forEach(([groupJid, key]) => {
          if (groupJid && key && key.id) {
            lastSentMessageKeys.set(groupJid, key);
          }
        });
      }
    }
  } catch (err) {
    logger.warn({ err }, 'Could not load last_sent_keys.json, starting fresh.');
  }
}

function saveLastSentKey(groupJid, messageKey) {
  if (!groupJid || !messageKey || !messageKey.id) return;
  lastSentMessageKeys.set(groupJid, messageKey);
  try {
    const plainObj = Object.fromEntries(lastSentMessageKeys.entries());
    fs.writeFileSync(LAST_SENT_KEYS_FILE, JSON.stringify(plainObj, null, 2), 'utf-8');
  } catch (err) {
    logger.warn({ err }, 'Could not persist last_sent_keys.json');
  }
}

/**
 * Deletes the previous bot post in [groupJid] for everyone (if one exists),
 * then sends the new media post and records its message key for the next send.
 */
async function sendGroupMediaWithAutoDeletePrevious(groupJid, imageBuffer, mimeType, caption) {
  const previousKey = lastSentMessageKeys.get(groupJid);
  if (previousKey && previousKey.id) {
    try {
      await sock.sendMessage(groupJid, { delete: previousKey });
      logger.info(
        { groupJid, deletedMessageId: previousKey.id },
        'Auto-deleted previous bot post for everyone in group.'
      );
    } catch (delErr) {
      logger.warn(
        { delErr, groupJid, previousMessageId: previousKey.id },
        'Failed to auto-delete previous bot post (proceeding with new send).'
      );
    }
  }

  const sentMsg = await sock.sendMessage(groupJid, {
    image: imageBuffer,
    mimetype: mimeType || 'image/jpeg',
    caption: caption || ''
  });

  if (sentMsg && sentMsg.key) {
    saveLastSentKey(groupJid, sentMsg.key);
  }

  return sentMsg;
}

loadProcessedTasks();
loadAutoRules();
loadLastSentKeys();

// Global WhatsApp Connection State
let sock = null;
let connectionState = 'DISCONNECTED'; // 'CONNECTED' | 'QR_READY' | 'DISCONNECTED'
let latestQrBase64 = null;
let latestRawQr = null;
let reconnectTimer = null;
const groupSubjectCache = new Map(); // groupJid -> subject

/**
 * Initializes and maintains the persistent Baileys WhatsApp Web Socket.
 */
async function initWhatsAppConnection() {
  try {
    if (!fs.existsSync(AUTH_DIR)) {
      fs.mkdirSync(AUTH_DIR, { recursive: true });
    }

    const { state, saveCreds } = await useMultiFileAuthState(AUTH_DIR);

    let version = [2, 3000, 1015901307];
    try {
      const latest = await fetchLatestBaileysVersion();
      if (latest && latest.version) {
        version = latest.version;
      }
    } catch (versionErr) {
      logger.warn('Using fallback WhatsApp Web version because fetchLatestBaileysVersion failed.');
    }

    sock = makeWASocket({
      version,
      logger: baileysLogger,
      printQRInTerminal: false,
      auth: state,
      browser: Browsers.ubuntu('HybridSchedulerBot'),
      syncFullHistory: false,
      markOnlineOnConnect: false,
      generateHighQualityLinkPreview: true
    });

    sock.ev.on('creds.update', saveCreds);

    sock.ev.on('connection.update', async (update) => {
      const { connection, lastDisconnect, qr } = update;

      if (qr) {
        latestRawQr = qr;
        connectionState = 'QR_READY';
        try {
          latestQrBase64 = await QRCode.toDataURL(qr, {
            errorCorrectionLevel: 'M',
            margin: 2,
            width: 360
          });
        } catch (qrErr) {
          logger.error({ qrErr }, 'Failed to convert QR code to base64 Data URL');
        }
        logger.info('New WhatsApp QR Code generated. Scan from WhatsApp -> Linked Devices.');
        qrcodeTerminal.generate(qr, { small: true });
      }

      if (connection === 'open') {
        connectionState = 'CONNECTED';
        latestQrBase64 = null;
        latestRawQr = null;
        if (reconnectTimer) {
          clearTimeout(reconnectTimer);
          reconnectTimer = null;
        }
        logger.info(
          { user: sock?.user },
          'WhatsApp Background Bot successfully connected and ready.'
        );
        // Warm up group cache in background
        refreshGroupCache().catch(() => {});
      }

      if (connection === 'close') {
        connectionState = 'DISCONNECTED';
        latestQrBase64 = null;
        latestRawQr = null;

        const statusCode = lastDisconnect?.error?.output?.statusCode;
        const shouldReconnect = statusCode !== DisconnectReason.loggedOut;

        logger.warn({ statusCode, shouldReconnect }, 'WhatsApp connection closed.');

        if (statusCode === DisconnectReason.loggedOut) {
          logger.warn('Session logged out from phone. Clearing auth_info_baileys for fresh QR...');
          try {
            fs.rmSync(AUTH_DIR, { recursive: true, force: true });
          } catch (rmErr) {
            logger.error({ rmErr }, 'Failed to remove auth directory');
          }
          reconnectTimer = setTimeout(() => initWhatsAppConnection(), 2500);
        } else if (shouldReconnect) {
          reconnectTimer = setTimeout(() => initWhatsAppConnection(), 4000);
        }
      }
    });

    // =========================================================================
    // 24/7 REAL-TIME GROUP ACTIVITY AUTO-RESPONDER (messages.upsert)
    // =========================================================================
    sock.ev.on('messages.upsert', async ({ messages, type }) => {
      if (type !== 'notify' || !Array.isArray(messages)) return;

      for (const msg of messages) {
        try {
          await handleIncomingGroupMessageForAutoRules(msg);
        } catch (err) {
          logger.error({ err }, 'Error evaluating incoming group message for auto-rules');
        }
      }
    });
  } catch (err) {
    logger.error({ err }, 'Error initializing WhatsApp socket, retrying in 5 seconds...');
    connectionState = 'DISCONNECTED';
    reconnectTimer = setTimeout(() => initWhatsAppConnection(), 5000);
  }
}

async function refreshGroupCache() {
  if (!sock || connectionState !== 'CONNECTED') return;
  const groupsMap = await sock.groupFetchAllParticipating();
  Object.values(groupsMap || {}).forEach((g) => {
    if (g && g.id && g.subject) {
      groupSubjectCache.set(g.id, g.subject);
    }
  });
}

async function getGroupSubjectByJid(remoteJid) {
  if (groupSubjectCache.has(remoteJid)) {
    return groupSubjectCache.get(remoteJid);
  }
  try {
    const meta = await sock.groupMetadata(remoteJid);
    if (meta && meta.subject) {
      groupSubjectCache.set(remoteJid, meta.subject);
      return meta.subject;
    }
  } catch (_) {
    // Ignore metadata lookup error
  }
  return '';
}

/**
 * Evaluates incoming group messages against synced Auto-Reply Rules when server-side
 * execution is enabled.
 */
async function handleIncomingGroupMessageForAutoRules(msg) {
  if (!msg || !msg.key || !msg.message) return;

  const remoteJid = msg.key.remoteJid || '';
  if (!remoteJid.endsWith('@g.us')) return; // Ignore non-group messages
  if (msg.key.fromMe) return; // Never react to our own messages

  // Ignore protocol / reaction / receipt / stub messages
  const messageTypes = Object.keys(msg.message);
  const isRealUserPost = messageTypes.some((t) =>
    [
      'conversation',
      'extendedTextMessage',
      'imageMessage',
      'videoMessage',
      'documentMessage',
      'audioMessage'
    ].includes(t)
  );
  if (!isRealUserPost) return;

  // Anti-self-loop guard: ignore if bot sent to this group in the last 15 seconds
  const now = Date.now();
  const lastBotSend = recentBotGroupSends.get(remoteJid) || 0;
  if (now - lastBotSend < 15000) {
    return;
  }

  // Burst debounce guard: ignore if a 5-second delay timer is already pending for this group
  if (pendingGroupTimers.has(remoteJid)) {
    logger.debug({ remoteJid }, 'Group already has a pending 5-second auto-reply timer. Ignoring burst message.');
    return;
  }

  const enabledRules = Array.from(autoRulesMap.values()).filter(
    (r) => r.isEnabled && r.serverAutoExecute !== false
  );
  if (enabledRules.length === 0) return;

  const groupSubject = await getGroupSubjectByJid(remoteJid);
  const normalizedSubject = (groupSubject || '').trim().toLowerCase();

  for (const rule of enabledRules) {
    let isMatch = false;
    if (rule.matchAllGroups) {
      isMatch = true;
    } else if (rule.targetGroupJid && rule.targetGroupJid.trim() === remoteJid) {
      isMatch = true;
    } else if (rule.targetGroupName && normalizedSubject) {
      const targetNorm = rule.targetGroupName.trim().toLowerCase();
      isMatch =
        normalizedSubject === targetNorm || normalizedSubject.includes(targetNorm);
    }

    if (!isMatch) continue;

    // Check per-rule, per-group cooldown
    const cooldownKey = `${rule.ruleId}_${remoteJid}`;
    const lastTriggered = Math.max(
      autoCooldownMap.get(cooldownKey) || 0,
      lastSentTimestamps.get(remoteJid) || 0
    );
    const cooldownMs = Number(rule.cooldownMillis) || 120000;

    if (now - lastTriggered < cooldownMs) {
      logger.debug(
        { ruleId: rule.ruleId, remoteJid, groupSubject },
        'Auto-rule matched group post, but cooldown is still active.'
      );
      continue;
    }

    if (!rule.imageFilePath || !fs.existsSync(rule.imageFilePath)) {
      logger.warn({ ruleId: rule.ruleId }, 'Auto-rule image file missing on server disk.');
      continue;
    }

    // Lock pending timer & cooldown timestamps BEFORE starting the 5-second wait
    pendingGroupTimers.add(remoteJid);
    autoCooldownMap.set(cooldownKey, now);
    lastSentTimestamps.set(remoteJid, now);

    try {
      logger.info(
        { ruleId: rule.ruleId, remoteJid, groupSubject },
        `Group activity detected in [${groupSubject || remoteJid}]. Waiting 5 seconds before sending...`
      );

      // Wait 5 seconds (5000ms) before triggering the reply post
      await new Promise((resolve) => setTimeout(resolve, 5000));

      recentBotGroupSends.set(remoteJid, Date.now());
      const imageBuffer = fs.readFileSync(rule.imageFilePath);
      await sendGroupMediaWithAutoDeletePrevious(
        remoteJid,
        imageBuffer,
        rule.mimeType || 'image/jpeg',
        rule.caption || ''
      );

      rule.triggerCount = (Number(rule.triggerCount) || 0) + 1;
      rule.lastTriggeredAtMillis = Date.now();
      rule.lastTriggeredGroup = groupSubject || remoteJid;
      saveAutoRules();

      logger.info(
        { ruleId: rule.ruleId, remoteJid, groupSubject },
        'Auto-Responder Rule triggered after 5s smart delay and delivered group media post via Baileys!'
      );
    } finally {
      pendingGroupTimers.delete(remoteJid);
      recentBotGroupSends.set(remoteJid, Date.now());
    }
    break;
  }
}

/**
 * Helper to resolve a target Group JID (@g.us) from either an explicit JID or a group subject name.
 */
async function resolveGroupJid(groupName, explicitGroupJid) {
  if (explicitGroupJid && explicitGroupJid.trim().endsWith('@g.us')) {
    return explicitGroupJid.trim();
  }
  if (groupName && groupName.trim().endsWith('@g.us')) {
    return groupName.trim();
  }
  if (!groupName || !groupName.trim()) {
    throw new Error('Either a valid groupName or groupJid (@g.us) must be provided.');
  }

  if (!sock || connectionState !== 'CONNECTED') {
    throw new Error(`WhatsApp socket is not connected (current state: ${connectionState}).`);
  }

  const participatingGroups = await sock.groupFetchAllParticipating();
  const groupList = Object.values(participatingGroups || {});
  const normalizedTarget = groupName.trim().toLowerCase();

  // 1. Exact case-insensitive match on group subject
  const exactMatch = groupList.find(
    (g) => (g.subject || '').trim().toLowerCase() === normalizedTarget
  );
  if (exactMatch) {
    return exactMatch.id;
  }

  // 2. Fallback: Partial case-insensitive substring match
  const partialMatch = groupList.find((g) =>
    (g.subject || '').trim().toLowerCase().includes(normalizedTarget)
  );
  if (partialMatch) {
    return partialMatch.id;
  }

  throw new Error(
    `Target WhatsApp group "${groupName}" was not found among ${groupList.length} participating groups.`
  );
}

// ============================================================================
// REST API ENDPOINTS
// ============================================================================

app.get('/api/v1/status', async (req, res) => {
  try {
    return res.status(200).json({
      success: true,
      status: connectionState,
      qrCode: latestQrBase64,
      rawQr: latestRawQr,
      user: sock?.user
        ? {
            id: sock.user.id,
            name: sock.user.name || sock.user.verifiedName || 'Linked WhatsApp User'
          }
        : null,
      processedCount: processedTaskIds.size,
      activeAutoRulesCount: Array.from(autoRulesMap.values()).filter((r) => r.isEnabled).length,
      timestamp: Date.now()
    });
  } catch (err) {
    logger.error({ err }, 'GET /api/v1/status failed');
    return res.status(500).json({
      success: false,
      status: 'DISCONNECTED',
      error: err.message
    });
  }
});

app.post('/api/v1/pair-code', async (req, res) => {
  try {
    const { phoneNumber } = req.body;
    if (!phoneNumber) {
      return res.status(400).json({
        success: false,
        error: 'phoneNumber (in international format digits only, e.g. 923001234567) is required.'
      });
    }
    if (!sock) {
      return res.status(503).json({
        success: false,
        error: 'WhatsApp socket is not initialized yet.'
      });
    }
    const cleanPhone = String(phoneNumber).replace(/[^0-9]/g, '');
    const code = await sock.requestPairingCode(cleanPhone);
    return res.status(200).json({
      success: true,
      pairingCode: code,
      phoneNumber: cleanPhone
    });
  } catch (err) {
    logger.error({ err }, 'POST /api/v1/pair-code failed');
    return res.status(500).json({
      success: false,
      error: err.message
    });
  }
});

app.get('/api/v1/groups', async (req, res) => {
  try {
    if (!sock || connectionState !== 'CONNECTED') {
      return res.status(503).json({
        success: false,
        status: connectionState,
        error: `WhatsApp client is not connected (current status: ${connectionState}). Link your device first.`,
        groups: []
      });
    }

    const groupsMap = await sock.groupFetchAllParticipating();
    const groups = Object.values(groupsMap || {})
      .map((group) => {
        if (group.id && group.subject) {
          groupSubjectCache.set(group.id, group.subject);
        }
        return {
          id: group.id,
          subject: group.subject || 'Unnamed Group',
          participantsCount: Array.isArray(group.participants) ? group.participants.length : 0
        };
      })
      .sort((a, b) => a.subject.localeCompare(b.subject));

    return res.status(200).json({
      success: true,
      count: groups.length,
      groups
    });
  } catch (err) {
    logger.error({ err }, 'GET /api/v1/groups failed');
    return res.status(500).json({
      success: false,
      error: err.message,
      groups: []
    });
  }
});

app.post('/api/v1/send-group-media', upload.single('image'), async (req, res) => {
  const taskId = (req.body.taskId || '').trim();
  const groupName = (req.body.groupName || '').trim();
  const groupJid = (req.body.groupJid || '').trim();
  const caption = req.body.caption || '';

  if (!taskId) {
    return res.status(400).json({
      success: false,
      error: 'Missing required field: taskId'
    });
  }

  // 1. Idempotency Guard: Check if already processed or currently in-flight
  if (processedTaskIds.has(taskId)) {
    logger.info({ taskId }, 'Duplicate taskId detected. Returning idempotent success without re-sending.');
    return res.status(200).json({
      success: true,
      duplicate: true,
      taskId,
      messageId: 'IDEMPOTENT_SKIP',
      timestamp: Date.now()
    });
  }

  if (inFlightTaskIds.has(taskId)) {
    logger.info({ taskId }, 'Task is already in-flight. Preventing concurrent duplicate send.');
    return res.status(200).json({
      success: true,
      duplicate: true,
      taskId,
      messageId: 'IN_FLIGHT_SKIP',
      timestamp: Date.now()
    });
  }

  if (!req.file || !req.file.buffer) {
    return res.status(400).json({
      success: false,
      error: 'Missing required image binary file in multipart field "image".'
    });
  }

  if (!sock || connectionState !== 'CONNECTED') {
    return res.status(503).json({
      success: false,
      error: `WhatsApp Background Server is not connected (status: ${connectionState}).`
    });
  }

  inFlightTaskIds.add(taskId);

  try {
    const resolvedJid = await resolveGroupJid(groupName, groupJid);
    recentBotGroupSends.set(resolvedJid, Date.now());

    logger.info(
      {
        taskId,
        groupName,
        resolvedJid,
        imageBytes: req.file.buffer.length
      },
      'Sending scheduled media message to WhatsApp group via Baileys...'
    );

    const sendResult = await sendGroupMediaWithAutoDeletePrevious(
      resolvedJid,
      req.file.buffer,
      req.file.mimetype || 'image/jpeg',
      caption
    );

    saveProcessedTask(taskId);
    inFlightTaskIds.delete(taskId);

    const messageId = sendResult?.key?.id || `baileys_${Date.now()}`;
    logger.info({ taskId, resolvedJid, messageId }, 'Scheduled media message delivered successfully.');

    return res.status(200).json({
      success: true,
      duplicate: false,
      taskId,
      groupJid: resolvedJid,
      messageId,
      timestamp: Date.now()
    });
  } catch (err) {
    inFlightTaskIds.delete(taskId);
    logger.error({ err, taskId, groupName }, 'POST /api/v1/send-group-media failed');
    return res.status(500).json({
      success: false,
      taskId,
      error: err.message || 'Internal error while sending WhatsApp group media.'
    });
  }
});

// ============================================================================
// AUTO-RESPONDER RULES SYNC ENDPOINTS (/api/v1/auto-rules)
// ============================================================================

app.get('/api/v1/auto-rules', (req, res) => {
  const rules = Array.from(autoRulesMap.values()).map((r) => ({
    ruleId: r.ruleId,
    targetGroupName: r.targetGroupName,
    targetGroupJid: r.targetGroupJid || null,
    matchAllGroups: Boolean(r.matchAllGroups),
    caption: r.caption || '',
    cooldownMillis: Number(r.cooldownMillis) || 120000,
    isEnabled: Boolean(r.isEnabled),
    triggerCount: Number(r.triggerCount) || 0,
    lastTriggeredAtMillis: Number(r.lastTriggeredAtMillis) || 0,
    lastTriggeredGroup: r.lastTriggeredGroup || null
  }));
  return res.status(200).json({
    success: true,
    count: rules.length,
    rules
  });
});

app.post('/api/v1/auto-rules', upload.single('image'), (req, res) => {
  try {
    const ruleId = (req.body.ruleId || '').trim();
    if (!ruleId) {
      return res.status(400).json({ success: false, error: 'Missing required field: ruleId' });
    }

    const existing = autoRulesMap.get(ruleId);
    let imageFilePath = existing?.imageFilePath || null;
    let mimeType = existing?.mimeType || 'image/jpeg';

    if (req.file && req.file.buffer) {
      const ext = (req.file.originalname || 'img.jpg').split('.').pop() || 'jpg';
      imageFilePath = path.join(AUTO_MEDIA_DIR, `rule_${ruleId}.${ext}`);
      fs.writeFileSync(imageFilePath, req.file.buffer);
      mimeType = req.file.mimetype || 'image/jpeg';
    }

    const matchAllGroups =
      String(req.body.matchAllGroups).toLowerCase() === 'true' ||
      req.body.targetGroupName === 'ALL_GROUPS';

    const ruleObj = {
      ruleId,
      targetGroupName: (req.body.targetGroupName || 'ALL_GROUPS').trim(),
      targetGroupJid: (req.body.targetGroupJid || '').trim() || null,
      matchAllGroups,
      caption: req.body.caption || '',
      cooldownMillis: Number(req.body.cooldownMillis) || 120000,
      isEnabled: String(req.body.isEnabled ?? 'true').toLowerCase() !== 'false',
      // By default Android NotificationListener handles triggers using the Hybrid Engine;
      // serverAutoExecute can be enabled when the phone is completely powered off.
      serverAutoExecute: String(req.body.serverAutoExecute ?? 'false').toLowerCase() === 'true',
      imageFilePath,
      mimeType,
      triggerCount: existing?.triggerCount || 0,
      lastTriggeredAtMillis: existing?.lastTriggeredAtMillis || 0,
      lastTriggeredGroup: existing?.lastTriggeredGroup || null,
      updatedAtMillis: Date.now()
    };

    autoRulesMap.set(ruleId, ruleObj);
    saveAutoRules();

    return res.status(200).json({
      success: true,
      rule: ruleObj
    });
  } catch (err) {
    logger.error({ err }, 'POST /api/v1/auto-rules failed');
    return res.status(500).json({
      success: false,
      error: err.message
    });
  }
});

app.delete('/api/v1/auto-rules/:ruleId', (req, res) => {
  const { ruleId } = req.params;
  const existing = autoRulesMap.get(ruleId);
  if (existing && existing.imageFilePath && fs.existsSync(existing.imageFilePath)) {
    try {
      fs.unlinkSync(existing.imageFilePath);
    } catch (_) {}
  }
  autoRulesMap.delete(ruleId);
  saveAutoRules();
  return res.status(200).json({ success: true, ruleId });
});

app.post('/api/v1/logout', async (req, res) => {
  try {
    if (sock) {
      try {
        await sock.logout();
      } catch (_) {}
    }
    fs.rmSync(AUTH_DIR, { recursive: true, force: true });
    connectionState = 'DISCONNECTED';
    latestQrBase64 = null;
    setTimeout(() => initWhatsAppConnection(), 1000);
    return res.status(200).json({
      success: true,
      message: 'Session cleared. Reinitializing QR generation...'
    });
  } catch (err) {
    return res.status(500).json({
      success: false,
      error: err.message
    });
  }
});

app.listen(PORT, '0.0.0.0', () => {
  logger.info(`Hybrid WhatsApp Scheduler Backend listening on http://0.0.0.0:${PORT}`);
  initWhatsAppConnection();
});
