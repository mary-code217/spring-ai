const chatArea = document.getElementById('chatArea');
const input = document.getElementById('messageInput');
const sendBtn = document.getElementById('sendBtn');
const uploadArea = document.getElementById('uploadArea');
const uploadZone = document.getElementById('uploadZone');
const uploadStatus = document.getElementById('uploadStatus');
const modeBadge = document.getElementById('modeBadge');

let currentMode = 'chat'; // 'chat' or 'rag'

// 모드 전환
function setMode(mode) {
    currentMode = mode;
    document.querySelectorAll('.mode-tab').forEach((tab, i) => {
        tab.classList.toggle('active', (i === 0 && mode === 'chat') || (i === 1 && mode === 'rag'));
    });
    uploadArea.classList.toggle('show', mode === 'rag');
    modeBadge.className = `mode-badge ${mode}`;
    modeBadge.textContent = mode === 'chat' ? '일반' : 'RAG';
    input.placeholder = mode === 'chat' ? '메시지를 입력하세요...' : '문서에 대해 질문하세요...';
    addMessage(mode === 'chat' ? '일반 채팅 모드로 전환했습니다.' : 'RAG 모드로 전환했습니다. 먼저 문서를 업로드해주세요.', 'system');
}

// 드래그 앤 드롭
uploadZone.addEventListener('dragover', (e) => {
    e.preventDefault();
    uploadZone.classList.add('dragover');
});
uploadZone.addEventListener('dragleave', () => {
    uploadZone.classList.remove('dragover');
});
uploadZone.addEventListener('drop', (e) => {
    e.preventDefault();
    uploadZone.classList.remove('dragover');
    if (e.dataTransfer.files.length > 0) {
        uploadFile(e.dataTransfer.files[0]);
    }
});

// 파일 업로드
async function uploadFile(file) {
    if (!file) return;
    uploadStatus.textContent = `"${file.name}" 업로드 중...`;
    uploadStatus.className = 'upload-status';

    const formData = new FormData();
    formData.append('file', file);

    try {
        const res = await fetch('/api/documents', {
            method: 'POST',
            body: formData
        });

        if (!res.ok) throw new Error(`HTTP ${res.status}`);

        const data = await res.json();
        uploadStatus.textContent = `"${data.fileName}" 업로드 완료 (${data.chunksProcessed}개 청크 처리)`;
        uploadStatus.className = 'upload-status success';
        addMessage(`📄 "${data.fileName}" 문서가 처리되었습니다. (${data.chunksProcessed}개 청크)`, 'system');
    } catch (err) {
        uploadStatus.textContent = '업로드 실패: ' + err.message;
        uploadStatus.className = 'upload-status error';
    }

    document.getElementById('fileInput').value = '';
}

// 키보드 이벤트
input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        sendMessage();
    }
});

function addMessage(text, type) {
    const div = document.createElement('div');
    div.className = `message ${type}`;
    div.textContent = text;
    chatArea.appendChild(div);
    chatArea.scrollTop = chatArea.scrollHeight;
    return div;
}

function addLoading() {
    const div = document.createElement('div');
    div.className = 'message loading';
    div.innerHTML = '<span class="loading-dots">응답 생성 중</span>';
    chatArea.appendChild(div);
    chatArea.scrollTop = chatArea.scrollHeight;
    return div;
}

async function sendMessage() {
    const message = input.value.trim();
    if (!message) return;

    addMessage(message, 'user');
    input.value = '';
    input.style.height = 'auto';
    sendBtn.disabled = true;

    const loading = addLoading();
    const endpoint = currentMode === 'rag' ? '/api/rag/chat' : '/api/chat';

    try {
        const res = await fetch(endpoint, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ message })
        });

        if (!res.ok) throw new Error(`HTTP ${res.status}`);

        const data = await res.json();
        loading.remove();
        addMessage(data.answer, 'ai');
    } catch (err) {
        loading.remove();
        addMessage('오류가 발생했습니다: ' + err.message, 'ai');
    } finally {
        sendBtn.disabled = false;
        input.focus();
    }
}

input.focus();
