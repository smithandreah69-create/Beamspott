const net = require('net');
const crypto = require('crypto');

class MikrotikClient {
    constructor(host, port, username, password) {
        this.host = host;
        this.port = port || 8728;
        this.username = username;
        this.password = password;
        this.socket = null;
        this.buffer = Buffer.alloc(0);
        this.sentenceQueue = [];
        this.pendingResolve = null;
    }

    connect() {
        return new Promise((resolve, reject) => {
            this.socket = new net.Socket();
            this.socket.setTimeout(5000);

            this.socket.on('data', (chunk) => {
                this.buffer = Buffer.concat([this.buffer, chunk]);
                this.parseBuffer();
            });

            this.socket.on('error', (err) => {
                if (this.pendingResolve) {
                    const cb = this.pendingResolve;
                    this.pendingResolve = null;
                    cb.reject(err);
                } else {
                    reject(err);
                }
            });

            this.socket.on('timeout', () => {
                this.socket.destroy();
                reject(new Error('Connection timeout'));
            });

            this.socket.connect(this.port, this.host, () => {
                this.socket.setTimeout(0); // clear timeout after connect
                resolve();
            });
        });
    }

    parseBuffer() {
        while (true) {
            let offset = 0;
            let sentence = [];
            let error = false;

            while (offset < this.buffer.length) {
                const lenResult = this.decodeLength(offset);
                if (!lenResult) {
                    // Not enough data to read length
                    return;
                }

                const { length, nextOffset } = lenResult;
                if (nextOffset + length > this.buffer.length) {
                    // Not enough data to read full word
                    return;
                }

                if (length === 0) {
                    // Empty word: end of sentence
                    offset = nextOffset;
                    this.buffer = this.buffer.slice(offset);
                    this.sentenceQueue.push(sentence);
                    if (this.pendingResolve) {
                        const cb = this.pendingResolve;
                        this.pendingResolve = null;
                        cb.resolve(this.sentenceQueue.shift());
                    }
                    return;
                }

                const word = this.buffer.slice(nextOffset, nextOffset + length).toString('utf8');
                sentence.push(word);
                offset = nextOffset + length;
            }
        }
    }

    decodeLength(offset) {
        if (offset >= this.buffer.length) return null;
        const first = this.buffer[offset];

        if ((first & 0x80) === 0) {
            return { length: first, nextOffset: offset + 1 };
        } else if ((first & 0xC0) === 0x80) {
            if (offset + 1 >= this.buffer.length) return null;
            const second = this.buffer[offset + 1];
            const length = ((first & 0x3F) << 8) | second;
            return { length, nextOffset: offset + 2 };
        } else if ((first & 0xE0) === 0xC0) {
            if (offset + 2 >= this.buffer.length) return null;
            const second = this.buffer[offset + 1];
            const third = this.buffer[offset + 2];
            const length = ((first & 0x1F) << 16) | (second << 8) | third;
            return { length, nextOffset: offset + 3 };
        } else if ((first & 0xF0) === 0xE0) {
            if (offset + 3 >= this.buffer.length) return null;
            const second = this.buffer[offset + 1];
            const third = this.buffer[offset + 2];
            const fourth = this.buffer[offset + 3];
            const length = ((first & 0x0F) << 24) | (second << 16) | (third << 8) | fourth;
            return { length, nextOffset: offset + 4 };
        } else if (first === 0xF0) {
            if (offset + 4 >= this.buffer.length) return null;
            const second = this.buffer[offset + 1];
            const third = this.buffer[offset + 2];
            const fourth = this.buffer[offset + 3];
            const fifth = this.buffer[offset + 4];
            const length = (second << 24) | (third << 16) | (fourth << 8) | fifth;
            return { length, nextOffset: offset + 5 };
        }
        return null;
    }

    encodeLength(len) {
        if (len < 0x80) {
            return Buffer.from([len]);
        } else if (len < 0x4000) {
            return Buffer.from([(len >> 8) | 0x80, len & 0xFF]);
        } else if (len < 0x200000) {
            return Buffer.from([(len >> 16) | 0xC0, (len >> 8) & 0xFF, len & 0xFF]);
        } else if (len < 0x10000000) {
            return Buffer.from([(len >> 24) | 0xE0, (len >> 16) & 0xFF, (len >> 8) & 0xFF, len & 0xFF]);
        } else {
            return Buffer.from([0xF0, (len >> 24) & 0xFF, (len >> 16) & 0xFF, (len >> 8) & 0xFF, len & 0xFF]);
        }
    }

    writeSentence(words) {
        const buffers = [];
        for (const word of words) {
            const wordBuf = Buffer.from(word, 'utf8');
            buffers.push(this.encodeLength(wordBuf.length));
            buffers.push(wordBuf);
        }
        buffers.push(Buffer.from([0])); // terminative word
        this.socket.write(Buffer.concat(buffers));
    }

    readSentence() {
        if (this.sentenceQueue.length > 0) {
            return Promise.resolve(this.sentenceQueue.shift());
        }
        return new Promise((resolve, reject) => {
            this.pendingResolve = { resolve, reject };
        });
    }

    async readResponseGroup() {
        const group = [];
        while (true) {
            const sentence = await this.readSentence();
            group.push(sentence);
            const doneOrTrap = sentence.find(w => w.startsWith('!done') || w.startsWith('!trap'));
            if (doneOrTrap) {
                break;
            }
        }
        return group;
    }

    async login() {
        // Try direct login first (modern 6.43+ RouterOS)
        this.writeSentence(['/login', `=name=${this.username}`, `=password=${this.password}`]);
        let resp = await this.readResponseGroup();
        let last = resp[resp.length - 1];

        if (last.includes('!done')) {
            // direct login worked or challenge login response needed
            const retWord = last.find(w => w.startsWith('=ret='));
            if (retWord) {
                const challenge = retWord.substring(5);
                // Compute challenge MD5
                const chalBuf = Buffer.from(challenge, 'hex');
                const md5sum = crypto.createHash('md5');
                md5sum.update(Buffer.from([0]));
                md5sum.update(this.password);
                md5sum.update(chalBuf);
                const responseHex = md5sum.digest('hex');

                this.writeSentence(['/login', `=name=${this.username}`, `=response=00${responseHex}`]);
                resp = await this.readResponseGroup();
                last = resp[resp.length - 1];
                if (!last.includes('!done')) {
                    throw new Error('Authentication failed (challenge-response)');
                }
            }
        } else if (last.includes('!trap')) {
            throw new Error('Router API returned trap: ' + last.join(', '));
        } else {
            throw new Error('Login failed: ' + last.join(', '));
        }
    }

    async execute(cmd, args = {}) {
        const words = [cmd];
        for (const [k, v] of Object.entries(args)) {
            words.push(`=${k}=${v}`);
        }
        this.writeSentence(words);
        return await this.readResponseGroup();
    }

    disconnect() {
        if (this.socket) {
            this.socket.destroy();
            this.socket = null;
        }
    }
}

module.exports = MikrotikClient;
