(() => {
  const terminalElement = document.getElementById('terminal');
  const statusElement = document.getElementById('status');

  const terminal = new Terminal({
    cursorBlink: true,
    convertEol: true,
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace',
    fontSize: 13,
    theme: {
      background: '#05070d',
      foreground: '#e5e7eb',
      cursor: '#34d399',
      selectionBackground: '#334155',
    },
  });
  const fitAddon = new FitAddon.FitAddon();
  terminal.loadAddon(fitAddon);
  terminal.open(terminalElement);

  let socket = null;
  let resizeTimer = null;

  function setStatus(text, className) {
    statusElement.textContent = text;
    statusElement.className = `status ${className}`;
  }

  function wsUrl() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    return `${protocol}//${window.location.host}/ws/terminal${window.location.search}`;
  }

  function hasToken() {
    return new URLSearchParams(window.location.search).has('token');
  }

  function sendJson(message) {
    if (!socket || socket.readyState !== WebSocket.OPEN) {
      return;
    }
    socket.send(JSON.stringify(message));
  }

  function fitAndResizePty(attempt = 0) {
    fitAddon.fit();

    if (terminal.cols <= 0 || terminal.rows <= 0) {
      if (attempt < 5) {
        window.setTimeout(() => fitAndResizePty(attempt + 1), 80);
      }
      return;
    }

    sendJson({
      type: 'resize',
      cols: terminal.cols,
      rows: terminal.rows,
    });
  }

  function scheduleResize() {
    window.clearTimeout(resizeTimer);
    resizeTimer = window.setTimeout(() => fitAndResizePty(), 80);
  }

  function connect() {
    if (!hasToken()) {
      setStatus('missing token', 'status-closed');
      terminal.writeln('\x1b[31mMissing token. Open the URL printed by Mira logcat, including ?token=...\x1b[0m');
      return;
    }

    setStatus('connecting', 'status-waiting');
    terminal.writeln('\x1b[33mConnecting to Mira PTY...\x1b[0m');

    socket = new WebSocket(wsUrl());
    socket.binaryType = 'arraybuffer';

    socket.addEventListener('open', () => {
      setStatus('connected', 'status-open');
      window.requestAnimationFrame(() => fitAndResizePty());
      window.setTimeout(() => fitAndResizePty(), 120);
      terminal.focus();
    });

    socket.addEventListener('message', (event) => {
      if (event.data instanceof ArrayBuffer) {
        terminal.write(new Uint8Array(event.data));
      } else {
        terminal.write(event.data);
      }
    });

    socket.addEventListener('close', () => {
      setStatus('closed', 'status-closed');
      terminal.writeln('\r\n\x1b[31mConnection closed. Refresh the page to start a new PTY session.\x1b[0m');
    });

    socket.addEventListener('error', () => {
      setStatus('error', 'status-closed');
    });
  }

  terminal.onData((data) => {
    sendJson({ type: 'input', data });
  });

  window.addEventListener('resize', scheduleResize);

  if ('ResizeObserver' in window) {
    const resizeObserver = new ResizeObserver(scheduleResize);
    resizeObserver.observe(terminalElement);
  }

  if (document.fonts && document.fonts.ready) {
    document.fonts.ready.then(scheduleResize).catch(() => {});
  }

  connect();
})();
