import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';

const PROMO_DIR = '/home/rdkgl/yoplayer/promo';
const OUT_MP4 = path.join(PROMO_DIR, 'yoplayer_promo.mp4');
const AUDIO_PATH = path.join(PROMO_DIR, 'promo_audio.m4a');
const FPS = 30;
const DURATION = 28.0;
const TOTAL_FRAMES = Math.round(FPS * DURATION); // 840 frames
const CDP_PORT = 9229;

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.png': 'image/png',
  '.m4a': 'audio/mp4',
  '.js': 'application/javascript',
  '.css': 'text/css',
};

function startServer() {
  return new Promise((resolve) => {
    const server = http.createServer((req, res) => {
      const cleanUrl = (req.url || '/').split('?')[0];
      const filePath = path.join(PROMO_DIR, cleanUrl === '/' ? 'yoplayer_promo.html' : cleanUrl);
      if (!filePath.startsWith(PROMO_DIR) || !fs.existsSync(filePath)) {
        res.writeHead(404);
        res.end('Not found');
        return;
      }
      const ext = path.extname(filePath).toLowerCase();
      res.writeHead(200, { 'Content-Type': MIME[ext] || 'application/octet-stream' });
      fs.createReadStream(filePath).pipe(res);
    });
    server.listen(0, '127.0.0.1', () => {
      resolve({ server, port: server.address().port });
    });
  });
}

async function waitForCdp(port, timeoutMs = 15000) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    try {
      const r = await fetch(`http://127.0.0.1:${port}/json/list`);
      if (r.ok) {
        const list = await r.json();
        const page = list.find((t) => t.type === 'page' && t.webSocketDebuggerUrl);
        if (page) return page.webSocketDebuggerUrl;
      }
    } catch (_) {}
    await new Promise((r) => setTimeout(r, 150));
  }
  throw new Error('Timed out waiting for Chromium CDP');
}

class CdpClient {
  constructor(wsUrl) {
    this.wsUrl = wsUrl;
    this.nextId = 1;
    this.pending = new Map();
  }
  connect() {
    return new Promise((resolve, reject) => {
      this.ws = new WebSocket(this.wsUrl);
      this.ws.addEventListener('open', () => resolve());
      this.ws.addEventListener('error', (e) => reject(e));
      this.ws.addEventListener('message', (ev) => {
        const msg = JSON.parse(ev.data);
        if (msg.id && this.pending.has(msg.id)) {
          const { resolve: res, reject: rej } = this.pending.get(msg.id);
          this.pending.delete(msg.id);
          if (msg.error) rej(new Error(msg.error.message));
          else res(msg.result);
        }
      });
    });
  }
  send(method, params = {}) {
    return new Promise((resolve, reject) => {
      const id = this.nextId++;
      this.pending.set(id, { resolve, reject });
      this.ws.send(JSON.stringify({ id, method, params }));
    });
  }
  close() {
    try { this.ws.close(); } catch (_) {}
  }
}

async function main() {
  const { server, port } = await startServer();
  console.log(`HTTP server on http://127.0.0.1:${port}`);

  const chrome = spawn('/usr/bin/chromium', [
    '--headless=new',
    '--no-sandbox',
    '--user-data-dir=/tmp/yoplayer-chrome-profile',
    '--disable-dev-shm-usage',
    '--disable-background-timer-throttling',
    '--disable-renderer-backgrounding',
    '--hide-scrollbars',
    `--remote-debugging-port=${CDP_PORT}`,
    '--window-size=1080,1920',
    'about:blank',
  ], { stdio: ['ignore', 'ignore', 'pipe'] });

  let chromeErr = '';
  chrome.stderr.on('data', (d) => { chromeErr += d.toString(); });

  try {
    const wsUrl = await waitForCdp(CDP_PORT);
    const cdp = new CdpClient(wsUrl);
    await cdp.connect();

    await cdp.send('Page.enable');
    await cdp.send('Runtime.enable');
    await cdp.send('Emulation.setDeviceMetricsOverride', {
      width: 540,
      height: 960,
      deviceScaleFactor: 1.5,
      mobile: false,
    });

    await cdp.send('Page.navigate', { url: `http://127.0.0.1:${port}/yoplayer_promo.html` });

    // Wait until page, Tailwind CSS, fonts, cover.jpg, and logo.png are 100% loaded
    for (let i = 0; i < 60; i++) {
      const { result } = await cdp.send('Runtime.evaluate', {
        expression: `(() => {
          const imgs = Array.from(document.querySelectorAll('img'));
          const imgsOk = imgs.length > 0 && imgs.every(im => im.complete && im.naturalWidth > 0);
          const hasTw = !!document.querySelector('style[data-tailwindcss], style');
          return Boolean(window.__yoStudio && document.readyState === 'complete' && imgsOk && hasTw);
        })()`,
        returnByValue: true,
      });
      if (result && result.value) break;
      await new Promise((r) => setTimeout(r, 250));
    }

    await cdp.send('Runtime.evaluate', {
      expression: `(async () => {
        if (document.fonts && document.fonts.ready) await document.fonts.ready;
        window.__yoStudio.enterCapture('graphite');
        await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
        return true;
      })()`,
      awaitPromise: true,
    });

    const ffmpeg = spawn('/usr/bin/ffmpeg', [
      '-y',
      '-f', 'image2pipe',
      '-vcodec', 'mjpeg',
      '-framerate', String(FPS),
      '-i', 'pipe:0',
      '-i', AUDIO_PATH,
      '-vf', 'scale=1080:1920:flags=lanczos',
      '-c:v', 'libx264',
      '-preset', 'medium',
      '-crf', '17',
      '-pix_fmt', 'yuv420p',
      '-c:a', 'aac',
      '-b:a', '192k',
      '-movflags', '+faststart',
      '-shortest',
      OUT_MP4,
    ], { stdio: ['pipe', 'ignore', 'pipe'] });

    let ffErr = '';
    ffmpeg.stderr.on('data', (d) => { ffErr += d.toString(); });
    const ffDone = new Promise((resolve, reject) => {
      ffmpeg.on('close', (code) => {
        if (code === 0) resolve();
        else reject(new Error(`ffmpeg exited ${code}:\n${ffErr.slice(-800)}`));
      });
    });

    const t0 = Date.now();
    for (let f = 0; f < TOTAL_FRAMES; f++) {
      const t = f / FPS;
      await cdp.send('Runtime.evaluate', {
        expression: `window.__yoStudio.renderAt(${t.toFixed(5)})`,
      });
      const { data } = await cdp.send('Page.captureScreenshot', {
        format: 'jpeg',
        quality: 95,
      });
      const buf = Buffer.from(data, 'base64');
      if (!ffmpeg.stdin.write(buf)) {
        await new Promise((r) => ffmpeg.stdin.once('drain', r));
      }
      if ((f + 1) % 120 === 0 || f === TOTAL_FRAMES - 1) {
        const elapsed = ((Date.now() - t0) / 1000).toFixed(1);
        console.log(`Rendered ${f + 1}/${TOTAL_FRAMES} frames (${elapsed}s)`);
      }
    }

    ffmpeg.stdin.end();
    await ffDone;
    cdp.close();
    console.log(`SUCCESS: ${OUT_MP4}`);
  } catch (err) {
    console.error('Render failed:', err);
    if (chromeErr) console.error('Chrome stderr:', chromeErr.slice(-800));
    process.exitCode = 1;
  } finally {
    chrome.kill('SIGTERM');
    server.close();
  }
}

main();
