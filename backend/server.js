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
    // Keep last 5,000 task IDs on disk
    const serialized = JSON.stringify(Array.from(processedTaskIds).slice(-5000), null, 2);
    fs.writeFileSync(PROCESSED_TASKS_FILE, serialized, 'utf-8');
  } catch (err) {
    logger.warn({ err }, 'Could not persist processed_tasks.json');
  }
}

loadProcessedTasks();

// Global WhatsApp Connection State
let sock = null;
let connectionState = 'DISCONNECTED'; // 'CONNECTED' | 'QR_READY' | 'DISCONNECTED'
let latestQrBase64 = null;
let latestRawQr = null;
let reconnectTimer = null;

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
      }

      if (connection === 'close') {
        connectionState = 'DISCONNECTED';
        latestQrBase64 = null;
        latestRawQr = null;

        const statusCode = lastDisconnect?.error?.output?.statusCode;
        const shouldReconnect = statusCode !== DisconnectReason.loggedOut;

        logger.warn(
          { statusCode, shouldReconnect },
          'WhatsApp connection closed.'
        );

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
  } catch (err) {
    logger.error({ err }, 'Error initializing WhatsApp socket, retrying in 5 seconds...');
    connectionState = 'DISCONNECTED';
    reconnectTimer = setTimeout(() => initWhatsAppConnection(), 5000);
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

/**
 * GET /api/v1/status
 * Returns live connection state ('CONNECTED', 'QR_READY', 'DISCONNECTED') and Base64 QR code.
 */
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

/**
 * POST /api/v1/pair-code
 * Optional helper to request an 8-digit WhatsApp phone pairing code instead of scanning QR.
 */
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

/**
 * GET /api/v1/groups
 * Fetches all participating groups via sock.groupFetchAllParticipating() for Android autocomplete.
 */
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
      .map((group) => ({
        id: group.id,
        subject: group.subject || 'Unnamed Group',
        participantsCount: Array.isArray(group.participants) ? group.participants.length : 0
      }))
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

/**
 * POST /api/v1/send-group-media
 * Multipart endpoint receiving:
 * - taskId (UUID for strict idempotency)
 * - groupName (Subject name or JID)
 * - groupJid (Optional explicit @g.us JID)
 * - caption (Text caption)
 * - image (Binary file uploaded via Multer memoryStorage)
 */
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

    logger.info(
      {
        taskId,
        groupName,
        resolvedJid,
        imageBytes: req.file.buffer.length
      },
      'Sending scheduled media message to WhatsApp group via Baileys...'
    );

    const sendResult = await sock.sendMessage(resolvedJid, {
      image: req.file.buffer,
      mimetype: req.file.mimetype || 'image/jpeg',
      caption: caption
    });

    // Record taskId as completed for strict idempotency
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

/**
 * POST /api/v1/logout
 * Resets the current WhatsApp session and generates a new QR code.
 */
app.post('/api/v1/logout', async (req, res) => {
  try {
    if (sock) {
      try {
        await sock.logout();
      } catch (_) {
        // Ignore if already disconnected
      }
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

// Start Express Server & Baileys Socket
app.listen(PORT, '0.0.0.0', () => {
  logger.info(`Hybrid WhatsApp Scheduler Backend listening on http://0.0.0.0:${PORT}`);
  initWhatsAppConnection();
});
