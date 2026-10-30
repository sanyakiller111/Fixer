// Cloudflare Worker for Fixer's Telegram proxy (from Flowseal/tg-ws-proxy, docs/EN/CfWorker.md, MIT).
// Fixer opens wss://tg.<domain>/apiws?dst=<Telegram DC IP>; the Worker bridges that WebSocket to a
// plain TCP connection to the DC on port 443, Telegram's native transport.
import { connect } from "cloudflare:sockets";

// Only Telegram DCs, the same addresses as DC_IPS in TgBridge.kt: otherwise the Worker is an open TCP relay to
// any host:443 for anyone who knows the domain, and abuse would also burn the shared daily request quota.
const ALLOWED_DST = new Set([
	"149.154.175.50", "149.154.167.51", "149.154.175.100",
	"149.154.167.91", "149.154.171.5", "91.105.192.100",
]);

function toBytes(data) {
	if (data instanceof ArrayBuffer) {
		return new Uint8Array(data);
	}
	if (typeof data === "string") {
		return new TextEncoder().encode(data);
	}
	if (data && typeof data.arrayBuffer === "function") {
		return data.arrayBuffer().then((ab) => new Uint8Array(ab));
	}
	return new Uint8Array();
}

export default {
	async fetch(request) {
		if ((request.headers.get("Upgrade") || "").toLowerCase() !== "websocket") {
			return new Response("Expected websocket", { status: 426 });
		}

		const url = new URL(request.url);
		if (url.pathname !== "/apiws") {
			return new Response("Not found", { status: 404 });
		}

		const dst = url.searchParams.get("dst");
		if (!dst) {
			return new Response("Missing dst", { status: 400 });
		}
		if (!ALLOWED_DST.has(dst)) {
			return new Response("Forbidden", { status: 403 });
		}
		const pair = new WebSocketPair();
		const client = pair[0];
		const server = pair[1];
		server.accept();

		const socket = connect({ hostname: dst, port: 443 });
		const tcpReader = socket.readable.getReader();
		const tcpWriter = socket.writable.getWriter();

		server.addEventListener("message", async (event) => {
			try {
				await tcpWriter.write(await toBytes(event.data));
			} catch {
				try {
					server.close(1011, "tcp write failed");
				} catch {}
			}
		});

		server.addEventListener("close", async () => {
			try {
				await tcpWriter.close();
			} catch {}
			try {
				socket.close();
			} catch {}
		});

		(async () => {
			try {
				while (true) {
					const { value, done } = await tcpReader.read();
					if (done) {
						break;
					}
					if (value) {
						server.send(value);
					}
				}
			} catch {
			} finally {
				try {
					server.close();
				} catch {}
				try {
					tcpReader.releaseLock();
				} catch {}
				try {
					socket.close();
				} catch {}
			}
		})();

		return new Response(null, { status: 101, webSocket: client });
	},
};
