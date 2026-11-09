'use strict';
const $ = id => document.getElementById(id);
function render(state) {
  const setup = state.stage === 'setup';
  $('setup-panel').hidden = !setup;
  $('progress-panel').hidden = setup;
  $('status').textContent = state.message;
  const error = state.stage === 'error';
  document.body.classList.toggle('error', error);
  $('progress-title').textContent = error ? '启动需要你的帮助' : state.stage === 'stopping' ? '正在关闭工作台' : '正在准备工作台';
  $('retry').hidden = !error;
  $('edit-config').hidden = !error;
  for (const id of ['setup', 'database', 'backend']) $('step-' + id).removeAttribute('aria-current');
  $('step-' + (setup || error ? 'setup' : state.stage === 'database' ? 'database' : 'backend')).setAttribute('aria-current', 'step');
}
function preset(local) {
  $('chatUrl').value = local ? 'http://127.0.0.1:11434/v1' : 'https://api.example.com/v1';
  $('chatModel').value = local ? 'qwen2.5:7b' : '';
  $('embeddingUrl').value = 'http://127.0.0.1:11434/v1';
  $('embeddingModel').value = 'bge-m3';
  $('chatUrl').focus();
}
$('local-preset').addEventListener('click', () => preset(true));
$('cloud-preset').addEventListener('click', () => preset(false));
$('logs').addEventListener('click', () => window.desktop.openLogs());
$('retry').addEventListener('click', () => window.desktop.retry());
$('edit-config').addEventListener('click', () => render({ stage: 'setup', message: '' }));
$('config-form').addEventListener('submit', async event => {
  event.preventDefault();
  $('save').disabled = true;
  $('form-error').hidden = true;
  try {
    const result = await window.desktop.configure(Object.fromEntries(new FormData(event.target)));
    if (!result.ok) {
      $('form-error').textContent = result.error;
      $('form-error').hidden = false;
      $('form-error').focus();
    }
  } catch {
    $('form-error').textContent = '保存未完成，请重新启动应用后重试。';
    $('form-error').hidden = false;
    $('form-error').focus();
  } finally { $('save').disabled = false; }
});
window.desktop.onStatus(render);
window.desktop.state().then(state => {
  $('version').textContent = 'v' + state.version;
  $('data-directory').textContent = state.dataDirectory;
  $('encryption-note').textContent = state.encryptionAvailable
    ? '模型凭据使用系统提供的加密服务保存在本机。'
    : '系统凭据加密暂不可用；配置将保存在仅当前用户可读的数据目录中。';
  render(state);
}).catch(() => render({ stage: 'error', message: '无法读取本地配置，请重新启动应用。' }));
