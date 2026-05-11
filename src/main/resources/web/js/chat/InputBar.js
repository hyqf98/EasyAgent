/**
 * 底部输入栏组件。
 * <p>
 * 使用 contenteditable 输入区承载文本和文件/图片引用 chip，
 * 以支持光标位置插入、原子删除和内联样式。
 * </p>
 *
 * @component input-bar
 */
var EA_FILE_SEARCH_REQUEST_COUNTER = 0;
var EA_SLASH_COMMAND_REQUEST_COUNTER = 0;
var EA_IMAGE_REFERENCE_COUNTER = 0;
var EA_REFERENCE_INSTANCE_COUNTER = 0;
var EA_INLINE_REFERENCE_PREFIX = '[[EA_REF_';
var EA_INLINE_REFERENCE_SUFFIX = ']]';
var EA_INLINE_TOKEN_SELECTOR = '.input-inline-token';
var EA_REFERENCE_SELECTOR = '.input-inline-ref';
var EA_COMMAND_SELECTOR = '.input-inline-command';

function EAIsInlineTokenNode(node) {
    return !!node
        && node.nodeType === Node.ELEMENT_NODE
        && node.classList
        && node.classList.contains('input-inline-token');
}

function EAIsReferenceNode(node) {
    return EAIsInlineTokenNode(node) && node.classList.contains('input-inline-ref');
}

function EAIsCommandNode(node) {
    return EAIsInlineTokenNode(node) && node.classList.contains('input-inline-command');
}

function EAGetInlineTokenText(node) {
    return EAIsInlineTokenNode(node)
        ? ((node.dataset && node.dataset.inlineToken) || '')
        : '';
}

function EAGetInlineTokenSerializedLength(node) {
    return EAGetInlineTokenText(node).length;
}

function EAGetSerializedNodeLength(node) {
    if (!node) {
        return 0;
    }
    if (EAIsInlineTokenNode(node)) {
        return EAGetInlineTokenSerializedLength(node);
    }
    if (node.nodeType === Node.TEXT_NODE) {
        return (node.nodeValue || '').length;
    }
    if (node.nodeName === 'BR') {
        return 1;
    }
    var length = 0;
    var children = node.childNodes || [];
    for (var i = 0; i < children.length; i++) {
        length += EAGetSerializedNodeLength(children[i]);
    }
    return length;
}

function EASerializeNode(node) {
    if (!node) {
        return '';
    }
    if (EAIsInlineTokenNode(node)) {
        return EAGetInlineTokenText(node);
    }
    if (node.nodeType === Node.TEXT_NODE) {
        return node.nodeValue || '';
    }
    if (node.nodeName === 'BR') {
        return '\n';
    }
    var text = '';
    var children = node.childNodes || [];
    for (var i = 0; i < children.length; i++) {
        text += EASerializeNode(children[i]);
    }
    return text;
}

window.EARegisterComponent('input-bar', 'InputBar', {
    props: {
        isStreaming: { type: Boolean, default: false },
        disabled: { type: Boolean, default: false }
    },
    emits: ['send', 'stop'],
    data() {
        return {
            isComposing: false,
            showModelDropdown: false,
            showReasoningDropdown: false,
            fileReferences: [],
            referenceRegistry: {},
            composerEmpty: true,
            showFileSearch: false,
            fileSearchQuery: '',
            fileSearchResults: [],
            fileSearchLoading: false,
            activeFileIndex: 0,
            activeMentionRange: null,
            pendingFileSearchRequestId: '',
            availableCommands: [],
            showCommandSearch: false,
            commandSearchQuery: '',
            commandSearchResults: [],
            activeCommandIndex: 0,
            activeSlashRange: null,
            pendingSlashCommandRequestId: '',
            fileSearchTimer: null,
            lastSelectionRange: null,
            pendingInsertRange: null,
            _commandJustInserted: false
        };
    },
    computed: {
        store() { return window.EAStore; },
        i18n() { void this.store.i18nVersion; return window.EAi18n; },
        placeholder() {
            return this.i18n.t('input.placeholder');
        },
        currentModels() {
            var list = this.store.modelsList || [];
            var cliType = this.store.cliType;
            return list.filter(function (m) { return m.cliType === cliType; });
        },
        currentModelLabel() {
            if (!this.store.selectedModelId) return this.i18n.t('chat.defaultModel');
            var models = this.currentModels;
            var selected = this.store.selectedModelId;
            for (var i = 0; i < models.length; i++) {
                if (models[i].modelId === selected) return models[i].displayName || models[i].modelId;
            }
            return selected;
        },
        currentReasoningLevels() {
            var map = this.store.reasoningLevelsMap || {};
            var cliType = this.store.cliType;
            return map[cliType] || [];
        },
        currentReasoningLabel() {
            var level = this.store.selectedReasoningLevel;
            if (!level) return this.i18n.t('chat.reasoningDefault');
            return this.i18n.t('chat.reasoning_' + level) || level;
        }
    },
    mounted() {
        this._onClickOutside = function (e) {
            if (this.showModelDropdown && !e.target.closest('.model-dropdown-wrapper')) {
                this.showModelDropdown = false;
            }
            if (this.showReasoningDropdown && !e.target.closest('.reasoning-dropdown-wrapper')) {
                this.showReasoningDropdown = false;
            }
            if (this.showFileSearch && !e.target.closest('.input-wrapper')) {
                this.closeFileSearch();
            }
            if (this.showCommandSearch && !e.target.closest('.input-wrapper')) {
                this.closeCommandSearch();
            }
        }.bind(this);
        this._onInsertReferences = function (e) {
            var refs = (e.detail && e.detail.references) || [];
            this.insertReferencesAtCaret(refs);
            this.closeFileSearch();
            this.$nextTick(function () {
                this.focusComposer();
            }.bind(this));
        }.bind(this);
        this._onFileReferenceCandidates = function (e) {
            var detail = e.detail || {};
            if (detail.requestId !== this.pendingFileSearchRequestId) {
                return;
            }
            this.fileSearchLoading = false;
            this.fileSearchResults = detail.results || [];
            this.activeFileIndex = 0;
        }.bind(this);
        this._onSlashCommands = function (e) {
            var detail = e.detail || {};
            if (detail.requestId && detail.requestId !== this.pendingSlashCommandRequestId) {
                return;
            }
            if (detail.cliType && detail.cliType !== this.store.cliType) {
                return;
            }
            this.availableCommands = detail.commands || [];
            this.commandSearchResults = this.filterCommandCandidates(this.commandSearchQuery);
            this.activeCommandIndex = 0;
            this.promoteLeadingSlashCommandToken();
            this.$nextTick(function () {
                this.scrollActiveCommandIntoView();
            }.bind(this));
        }.bind(this);
        document.addEventListener('click', this._onClickOutside);
        window.addEventListener('ea-insert-file-references', this._onInsertReferences);
        window.addEventListener('ea-file-reference-candidates', this._onFileReferenceCandidates);
        window.addEventListener('ea-slash-commands', this._onSlashCommands);
        this.requestSlashCommands();
        this.refreshComposerState();
    },
    beforeUnmount() {
        if (this._onClickOutside) {
            document.removeEventListener('click', this._onClickOutside);
        }
        if (this._onInsertReferences) {
            window.removeEventListener('ea-insert-file-references', this._onInsertReferences);
        }
        if (this._onFileReferenceCandidates) {
            window.removeEventListener('ea-file-reference-candidates', this._onFileReferenceCandidates);
        }
        if (this._onSlashCommands) {
            window.removeEventListener('ea-slash-commands', this._onSlashCommands);
        }
        if (this.fileSearchTimer) {
            clearTimeout(this.fileSearchTimer);
            this.fileSearchTimer = null;
        }
    },
    watch: {
        'store.cliType'() {
            this.availableCommands = [];
            this.closeCommandSearch();
            this.requestSlashCommands();
        }
    },
    methods: {
        selectModel(modelId) {
            this.store.selectedModelId = modelId;
            this.showModelDropdown = false;
        },
        selectReasoningLevel(level) {
            this.store.selectedReasoningLevel = level;
            this.showReasoningDropdown = false;
        },
        requestSlashCommands() {
            var cliType = this.store.cliType;
            if (!cliType) {
                return;
            }
            var requestId = 'sc-' + (++EA_SLASH_COMMAND_REQUEST_COUNTER);
            this.pendingSlashCommandRequestId = requestId;
            EABridge.getSlashCommands(cliType, requestId);
        },
        focusComposer() {
            var composer = this.$refs.composer;
            if (composer) {
                composer.focus();
            }
        },
        createReferenceInstanceId() {
            EA_REFERENCE_INSTANCE_COUNTER += 1;
            return 'ref-' + EA_REFERENCE_INSTANCE_COUNTER;
        },
        ensureInlineToken(reference) {
            if (reference.inlineToken) {
                return reference.inlineToken;
            }
            return EA_INLINE_REFERENCE_PREFIX + reference.instanceId + EA_INLINE_REFERENCE_SUFFIX;
        },
        buildReferenceLabel(reference) {
            if (!reference) {
                return 'file';
            }
            if (reference.referenceType === 'IMAGE') {
                return reference.displayName || reference.relativePath || reference.path || 'Image';
            }
            var label = reference.relativePath || reference.displayName || reference.path || 'file';
            if (reference.startLine && reference.endLine) {
                label += ':' + reference.startLine + '-' + reference.endLine;
            }
            return label;
        },
        normalizeReference(reference) {
            if (!reference || !reference.id) {
                return null;
            }
            var copy = Object.assign({}, reference);
            copy.referenceType = copy.referenceType || 'FILE';
            copy.instanceId = copy.instanceId || this.createReferenceInstanceId();
            copy.inlineToken = this.ensureInlineToken(copy);
            return copy;
        },
        createReferenceChip(reference) {
            var chip = document.createElement('span');
            chip.className = 'input-inline-token input-inline-ref' + (reference.referenceType === 'IMAGE' ? ' image-ref' : ' file-ref');
            chip.contentEditable = 'false';
            chip.dataset.refId = reference.id;
            chip.dataset.refInstanceId = reference.instanceId;
            chip.dataset.inlineToken = reference.inlineToken;
            chip.dataset.referenceType = reference.referenceType;
            chip.title = reference.path || reference.relativePath || reference.displayName || '';

            var icon = document.createElement('span');
            icon.className = 'input-inline-ref-icon';
            icon.textContent = reference.referenceType === 'IMAGE' ? 'IMG' : 'FILE';
            chip.appendChild(icon);

            var label = document.createElement('span');
            label.className = 'input-inline-ref-label';
            label.textContent = this.buildReferenceLabel(reference);
            chip.appendChild(label);

            var remove = document.createElement('span');
            remove.className = 'input-inline-ref-remove';
            remove.setAttribute('role', 'button');
            remove.setAttribute('aria-label', 'Remove reference');
            remove.textContent = '×';
            chip.appendChild(remove);

            return chip;
        },
        createCommandChip(command) {
            var chip = document.createElement('span');
            chip.className = 'input-inline-token input-inline-command';
            chip.contentEditable = 'false';
            chip.dataset.inlineToken = command.commandText || ('/' + command.name);
            chip.dataset.commandName = command.name || '';
            chip.dataset.commandSourceType = command.sourceType || '';
            chip.dataset.commandActionType = command.actionType || '';
            chip.title = command.description || chip.dataset.inlineToken;

            var icon = document.createElement('span');
            icon.className = 'input-inline-ref-icon';
            icon.textContent = 'CMD';
            chip.appendChild(icon);

            var label = document.createElement('span');
            label.className = 'input-inline-ref-label';
            label.textContent = chip.dataset.inlineToken;
            chip.appendChild(label);

            var remove = document.createElement('span');
            remove.className = 'input-inline-ref-remove';
            remove.setAttribute('role', 'button');
            remove.setAttribute('aria-label', 'Remove command');
            remove.textContent = '×';
            chip.appendChild(remove);

            return chip;
        },
        getComposerText() {
            var composer = this.$refs.composer;
            if (!composer) {
                return '';
            }
            var text = '';
            var children = composer.childNodes || [];
            for (var i = 0; i < children.length; i++) {
                text += EASerializeNode(children[i]);
            }
            return text;
        },
        getCurrentSelectionRange() {
            var composer = this.$refs.composer;
            var selection = window.getSelection ? window.getSelection() : null;
            if (!composer || !selection || selection.rangeCount === 0) {
                return null;
            }
            var range = selection.getRangeAt(0);
            if (!composer.contains(range.commonAncestorContainer) && range.commonAncestorContainer !== composer) {
                return null;
            }
            return range.cloneRange();
        },
        captureSelection() {
            this.lastSelectionRange = this.getCurrentSelectionRange();
        },
        createRangeAtComposerEnd() {
            var composer = this.$refs.composer;
            if (!composer) {
                return null;
            }
            var range = document.createRange();
            range.selectNodeContents(composer);
            range.collapse(false);
            return range;
        },
        createRangeAtComposerStart() {
            var composer = this.$refs.composer;
            if (!composer) {
                return null;
            }
            var range = document.createRange();
            range.selectNodeContents(composer);
            range.collapse(true);
            return range;
        },
        setSelection(range) {
            if (!range) {
                return;
            }
            var selection = window.getSelection ? window.getSelection() : null;
            if (!selection) {
                return;
            }
            selection.removeAllRanges();
            selection.addRange(range);
            this.lastSelectionRange = range.cloneRange();
        },
        ensureEmptyComposerCaret() {
            if (!this.composerEmpty) {
                return;
            }
            var range = this.createRangeAtComposerStart();
            if (range) {
                this.setSelection(range);
            }
        },
        getActiveRange() {
            return this.getCurrentSelectionRange()
                || (this.pendingInsertRange ? this.pendingInsertRange.cloneRange() : null)
                || (this.lastSelectionRange ? this.lastSelectionRange.cloneRange() : null)
                || this.createRangeAtComposerEnd();
        },
        insertTextAtSelection(text) {
            var range = this.getActiveRange();
            if (!range) {
                return;
            }
            range.deleteContents();
            var node = document.createTextNode(text);
            range.insertNode(node);
            range.setStart(node, node.nodeValue.length);
            range.collapse(true);
            this.setSelection(range);
            this.refreshComposerState();
        },
        insertInlineTokenAtSelection(node) {
            var range = this.getActiveRange();
            if (!range || !node) {
                return;
            }
            var spacer = document.createTextNode('');
            var fragment = document.createDocumentFragment();
            fragment.appendChild(node);
            fragment.appendChild(spacer);
            range.deleteContents();
            range.insertNode(fragment);
            range.setStart(spacer, spacer.nodeValue.length);
            range.collapse(true);
            this.setSelection(range);
            this.refreshComposerState();
        },
        insertNewlineAtSelection() {
            this.insertTextAtSelection('\n');
        },
        removeInlineTokenNode(chip) {
            if (!chip || !chip.parentNode) {
                return;
            }
            var parent = chip.parentNode;
            var nextSibling = chip.nextSibling;
            var range = document.createRange();
            chip.remove();
            if (nextSibling && nextSibling.parentNode === parent) {
                if (nextSibling.nodeType === Node.TEXT_NODE) {
                    range.setStart(nextSibling, 0);
                } else {
                    range.setStartBefore(nextSibling);
                }
            } else {
                range.selectNodeContents(parent);
                range.collapse(false);
            }
            range.collapse(true);
            this.setSelection(range);
            this.refreshComposerState();
        },
        findAdjacentInlineToken(direction) {
            var selection = window.getSelection ? window.getSelection() : null;
            if (!selection || selection.rangeCount === 0 || !selection.isCollapsed) {
                return null;
            }
            var composer = this.$refs.composer;
            if (!composer) {
                return null;
            }
            var node = selection.focusNode;
            var offset = selection.focusOffset;
            if (!node) {
                return null;
            }

            if (node === composer) {
                var child = composer.childNodes[direction === 'backward' ? offset - 1 : offset];
                return EAIsInlineTokenNode(child) ? child : null;
            }

            if (node.nodeType === Node.TEXT_NODE) {
                if (direction === 'backward' && offset > 0) {
                    return null;
                }
                if (direction === 'forward' && offset < (node.nodeValue || '').length) {
                    return null;
                }
                var sibling = direction === 'backward' ? node.previousSibling : node.nextSibling;
                return EAIsInlineTokenNode(sibling) ? sibling : null;
            }

            var index = direction === 'backward' ? offset - 1 : offset;
            var childNode = node.childNodes[index];
            return EAIsInlineTokenNode(childNode) ? childNode : null;
        },
        buildPointAtOffset(root, targetOffset) {
            var remaining = Math.max(0, targetOffset || 0);

            function locate(parent) {
                var children = parent.childNodes || [];
                for (var i = 0; i < children.length; i++) {
                    var child = children[i];
                    if (remaining <= 0) {
                        return { container: parent, offset: i };
                    }
                    var childLength = EAGetSerializedNodeLength(child);
                    if (remaining < childLength) {
                        if (EAIsInlineTokenNode(child) || child.nodeName === 'BR') {
                            return { container: parent, offset: i };
                        }
                        if (child.nodeType === Node.TEXT_NODE) {
                            return { container: child, offset: remaining };
                        }
                        return locate(child);
                    }
                    if (remaining === childLength) {
                        if (child.nodeType === Node.TEXT_NODE) {
                            return { container: child, offset: child.nodeValue.length };
                        }
                        return { container: parent, offset: i + 1 };
                    }
                    remaining -= childLength;
                }
                return { container: parent, offset: children.length };
            }

            return locate(root);
        },
        computeSerializedOffset(container, offset) {
            var composer = this.$refs.composer;
            if (!composer || !container) {
                return 0;
            }

            var total = 0;
            var found = false;

            function visit(node) {
                if (found || !node) {
                    return;
                }
                if (node === container) {
                    if (EAIsInlineTokenNode(node)) {
                        total += offset > 0 ? EAGetInlineTokenSerializedLength(node) : 0;
                        found = true;
                        return;
                    }
                    if (node.nodeType === Node.TEXT_NODE) {
                        total += Math.min(offset, (node.nodeValue || '').length);
                        found = true;
                        return;
                    }
                    var childNodes = node.childNodes || [];
                    for (var j = 0; j < Math.min(offset, childNodes.length); j++) {
                        total += EAGetSerializedNodeLength(childNodes[j]);
                    }
                    found = true;
                    return;
                }

                if (EAIsInlineTokenNode(node)) {
                    total += EAGetInlineTokenSerializedLength(node);
                    return;
                }
                if (node.nodeType === Node.TEXT_NODE) {
                    total += (node.nodeValue || '').length;
                    return;
                }
                if (node.nodeName === 'BR') {
                    total += 1;
                    return;
                }

                var children = node.childNodes || [];
                for (var i = 0; i < children.length; i++) {
                    visit(children[i]);
                    if (found) {
                        return;
                    }
                }
            }

            visit(composer);
            return total;
        },
        normalizeCommandText(value) {
            return (value || '').trim().toLowerCase();
        },
        findAvailableCommandByName(name) {
            var list = this.availableCommands || [];
            var normalized = this.normalizeCommandText(name);
            if (!normalized) {
                return null;
            }
            for (var i = 0; i < list.length; i++) {
                var item = list[i];
                if (!item) {
                    continue;
                }
                if (this.normalizeCommandText(item.name) === normalized) {
                    return item;
                }
                var aliases = item.aliases || [];
                for (var j = 0; j < aliases.length; j++) {
                    if (this.normalizeCommandText(aliases[j]) === normalized) {
                        return item;
                    }
                }
            }
            return null;
        },
        resolveLeadingSlashCommand(text) {
            var raw = text || '';
            var trimmed = raw.replace(/^\s+/, '');
            if (!trimmed.startsWith('/')) {
                return null;
            }
            var match = trimmed.match(/^\/([^\s/@]+)(?=\s|$)/);
            if (!match) {
                return null;
            }
            var command = this.findAvailableCommandByName(match[1]);
            if (!command) {
                return null;
            }
            var prefixLength = raw.length - trimmed.length;
            return {
                command: command,
                range: {
                    start: prefixLength,
                    end: prefixLength + match[0].length
                }
            };
        },
        parseActiveSlashCommand(beforeCaret) {
            if (!beforeCaret) {
                return null;
            }
            var match = beforeCaret.match(/^\s*\/([^\s/@]*)$/);
            if (!match) {
                return null;
            }
            var token = '/' + (match[1] || '');
            var start = beforeCaret.length - token.length;
            return {
                query: match[1] || '',
                range: {
                    start: start,
                    end: beforeCaret.length
                }
            };
        },
        commandMatchesQuery(command, query) {
            if (!command) {
                return false;
            }
            var normalizedQuery = this.normalizeCommandText(query);
            if (!normalizedQuery) {
                return true;
            }
            var name = this.normalizeCommandText(command.name || '');
            if (name.indexOf(normalizedQuery) >= 0) {
                return true;
            }
            if (this.normalizeCommandText(command.commandText || '').indexOf(normalizedQuery) >= 0) {
                return true;
            }
            var aliases = command.aliases || [];
            for (var i = 0; i < aliases.length; i++) {
                if (this.normalizeCommandText(aliases[i]).indexOf(normalizedQuery) >= 0) {
                    return true;
                }
            }
            return this.normalizeCommandText(command.description || '').indexOf(normalizedQuery) >= 0
                || this.normalizeCommandText(command.group || '').indexOf(normalizedQuery) >= 0
                || this.normalizeCommandText(command.sourceType || '').indexOf(normalizedQuery) >= 0;
        },
        filterCommandCandidates(query) {
            var list = this.availableCommands || [];
            var results = [];
            for (var i = 0; i < list.length; i++) {
                if (this.commandMatchesQuery(list[i], query)) {
                    results.push(list[i]);
                }
            }
            results.sort(function (a, b) {
                var groupCompare = (a.group || '').localeCompare(b.group || '');
                if (groupCompare !== 0) {
                    return groupCompare;
                }
                return (a.name || '').localeCompare(b.name || '');
            });
            return results;
        },
        closeCommandSearch() {
            this.showCommandSearch = false;
            this.commandSearchQuery = '';
            this.commandSearchResults = [];
            this.activeCommandIndex = 0;
            this.activeSlashRange = null;
        },
        isCaretWithinSlashRange() {
            if (!this.activeSlashRange) return false;
            var sel = window.getSelection ? window.getSelection() : null;
            if (!sel || sel.rangeCount === 0 || !sel.isCollapsed) return false;
            var caretOffset = this.computeSerializedOffset(sel.focusNode, sel.focusOffset);
            return caretOffset > this.activeSlashRange.start && caretOffset <= this.activeSlashRange.end;
        },
        moveActiveCommand(delta) {
            if (this.commandSearchResults.length === 0) return;
            var next = this.activeCommandIndex + delta;
            if (next < 0) next = this.commandSearchResults.length - 1;
            if (next >= this.commandSearchResults.length) next = 0;
            this.activeCommandIndex = next;
            this.$nextTick(function () {
                this.scrollActiveCommandIntoView();
            }.bind(this));
        },
        selectCommandCandidate(candidate) {
            if (!candidate || !candidate.name || !this.activeSlashRange) return;
            this.replaceSerializedRange(this.activeSlashRange.start, this.activeSlashRange.end, '');
            this.pendingInsertRange = this.getCurrentSelectionRange()
                || this.lastSelectionRange
                || this.createRangeAtComposerEnd();
            this.closeCommandSearch();
            this.insertInlineTokenAtSelection(this.createCommandChip(candidate), false);
            this.insertTextAtSelection(' ');
            this._commandJustInserted = true;
            this.$nextTick(function () {
                this._commandJustInserted = false;
                this.focusComposer();
            }.bind(this));
        },
        scrollActiveCommandIntoView() {
            var list = this.$refs.commandSearchList;
            if (!list) {
                return;
            }
            var active = list.querySelector('[data-command-index="' + this.activeCommandIndex + '"]');
            if (!active) {
                return;
            }
            var top = active.offsetTop;
            var bottom = top + active.offsetHeight;
            var viewTop = list.scrollTop;
            var viewBottom = viewTop + list.clientHeight;
            if (top < viewTop) {
                list.scrollTop = Math.max(0, top - 4);
            } else if (bottom > viewBottom) {
                list.scrollTop = bottom - list.clientHeight + 4;
            }
        },
        resolveSubmittedSlashCommand(text) {
            var raw = text || '';
            var trimmed = raw.replace(/^\s+/, '');
            if (!trimmed.startsWith('/')) {
                return null;
            }
            var firstLine = trimmed.split('\n')[0];
            var match = firstLine.match(/^\/([^\s]+)(?:\s+([\s\S]*))?$/);
            if (!match) {
                return null;
            }
            var name = match[1] || '';
            var command = this.findAvailableCommandByName(name);
            return {
                name: name,
                rawText: raw,
                command: command,
                executionType: command ? command.actionType : 'PASS_THROUGH'
            };
        },
        replaceSerializedRange(start, end, replacement) {
            var composer = this.$refs.composer;
            if (!composer) {
                return;
            }
            var startPoint = this.buildPointAtOffset(composer, start);
            var endPoint = this.buildPointAtOffset(composer, end);
            var range = document.createRange();
            range.setStart(startPoint.container, startPoint.offset);
            range.setEnd(endPoint.container, endPoint.offset);
            range.deleteContents();
            if (replacement) {
                var textNode = document.createTextNode(replacement);
                range.insertNode(textNode);
                range.setStart(textNode, textNode.nodeValue.length);
            }
            range.collapse(true);
            this.setSelection(range);
            this.refreshComposerState();
        },
        getMentionContext() {
            var range = this.getCurrentSelectionRange();
            if (!range || !range.collapsed) {
                return null;
            }
            var offset = this.computeSerializedOffset(range.startContainer, range.startOffset);
            var text = this.getComposerText();
            return {
                text: text,
                offset: offset,
                beforeCaret: text.slice(0, offset)
            };
        },
        refreshComposerState() {
            this.syncReferencesFromComposer();
            var serialized = this.getComposerText().replace(/\n/g, '');
            this.composerEmpty = !serialized && this.fileReferences.length === 0;
        },
        syncReferencesFromComposer() {
            var composer = this.$refs.composer;
            if (!composer) {
                this.fileReferences = [];
                this.referenceRegistry = {};
                return;
            }
            var nodes = composer.querySelectorAll(EA_REFERENCE_SELECTOR);
            var refs = [];
            var registry = {};
            for (var i = 0; i < nodes.length; i++) {
                var instanceId = nodes[i].dataset.refInstanceId;
                var reference = this.referenceRegistry[instanceId];
                if (!reference) {
                    continue;
                }
                refs.push(reference);
                registry[instanceId] = reference;
            }
            this.fileReferences = refs;
            this.referenceRegistry = registry;
        },
        openFileSearchShortcut() {
            this.focusComposer();
            this.insertTextAtSelection('@');
            this.syncFileSearchState();
        },
        handleComposerClick(e) {
            var removeBtn = e.target.closest ? e.target.closest('.input-inline-ref-remove') : null;
            if (removeBtn) {
                var chip = removeBtn.closest(EA_INLINE_TOKEN_SELECTOR);
                this.removeInlineTokenNode(chip);
                return;
            }
            this.handleCaretInteraction();
        },
        handleKeydown(e) {
            if (this.isComposing) return;
            if (this.showFileSearch) {
                if (e.key === 'ArrowDown') {
                    e.preventDefault();
                    this.moveActiveFile(1);
                    return;
                }
                if (e.key === 'ArrowUp') {
                    e.preventDefault();
                    this.moveActiveFile(-1);
                    return;
                }
                if (e.key === 'Escape') {
                    e.preventDefault();
                    this.closeFileSearch();
                    return;
                }
                if ((e.key === 'Enter' || e.key === 'Tab') && this.fileSearchResults.length > 0) {
                    e.preventDefault();
                    this.selectFileCandidate(this.fileSearchResults[this.activeFileIndex]);
                    return;
                }
                if (e.key === 'Enter' || e.key === 'Tab') {
                    e.preventDefault();
                    return;
                }
            }
            if (this.showCommandSearch) {
                if (e.key === 'ArrowDown') {
                    e.preventDefault();
                    this.moveActiveCommand(1);
                    return;
                }
                if (e.key === 'ArrowUp') {
                    e.preventDefault();
                    this.moveActiveCommand(-1);
                    return;
                }
                if (e.key === 'Escape') {
                    e.preventDefault();
                    this.closeCommandSearch();
                    return;
                }
                if ((e.key === 'Enter' || e.key === 'Tab') && this.commandSearchResults.length > 0) {
                    e.preventDefault();
                    this.selectCommandCandidate(this.commandSearchResults[this.activeCommandIndex]);
                    return;
                }
                if (e.key === 'Tab') {
                    e.preventDefault();
                    this.closeCommandSearch();
                    return;
                }
                if (e.key === 'Enter') {
                    this.closeCommandSearch();
                }
            }
            if (e.key === 'Backspace') {
                if (this.activeSlashRange && this.isCaretWithinSlashRange()) {
                    e.preventDefault();
                    this.replaceSerializedRange(this.activeSlashRange.start, this.activeSlashRange.end, '');
                    this.closeCommandSearch();
                    this.refreshComposerState();
                    return;
                }
                var prevChip = this.findAdjacentInlineToken('backward');
                if (prevChip) {
                    e.preventDefault();
                    this.removeInlineTokenNode(prevChip);
                    return;
                }
            }
            if (e.key === 'Delete') {
                var nextChip = this.findAdjacentInlineToken('forward');
                if (nextChip) {
                    e.preventDefault();
                    this.removeInlineTokenNode(nextChip);
                    return;
                }
            }
            if (e.key === 'Enter' && e.shiftKey) {
                e.preventDefault();
                this.insertNewlineAtSelection();
                return;
            }
            if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                this.send();
            }
        },
        handleKeyup(e) {
            if (this.isComposing) return;
            if (e && this.showFileSearch) {
                switch (e.key) {
                    case 'ArrowDown':
                    case 'ArrowUp':
                    case 'Enter':
                    case 'Tab':
                    case 'Escape':
                        return;
                    default:
                        break;
                }
            }
            if (e && this.showCommandSearch) {
                switch (e.key) {
                    case 'ArrowDown':
                    case 'ArrowUp':
                    case 'Enter':
                    case 'Tab':
                    case 'Escape':
                        return;
                    default:
                        break;
                }
            }
            this.handleCaretInteraction();
        },
        handleComposerFocus() {
            this.ensureEmptyComposerCaret();
            this.handleCaretInteraction();
        },
        handleCaretInteraction() {
            this.captureSelection();
            this.syncFileSearchState();
            this.syncCommandSearchState();
        },
        handlePaste(e) {
            var clipboard = e && e.clipboardData;
            if (!clipboard) {
                return;
            }
            var items = clipboard.items || [];
            for (var i = 0; i < items.length; i++) {
                var item = items[i];
                if (!item || !item.type || item.type.indexOf('image/') !== 0) {
                    continue;
                }
                var file = item.getAsFile ? item.getAsFile() : null;
                if (!file) {
                    continue;
                }
                e.preventDefault();
                this.captureSelection();
                this.pendingInsertRange = this.lastSelectionRange ? this.lastSelectionRange.cloneRange() : this.createRangeAtComposerEnd();
                this.saveClipboardImage(file);
                return;
            }

            var text = clipboard.getData ? clipboard.getData('text/plain') : '';
            if (text) {
                e.preventDefault();
                this.insertTextAtSelection(text);
                this.syncFileSearchState();
                this.syncCommandSearchState();
            }
        },
        handleCompositionStart() {
            this.isComposing = true;
        },
        handleCompositionEnd() {
            this.isComposing = false;
            this.handleCaretInteraction();
        },
        handleInput() {
            this.captureSelection();
            this.refreshComposerState();
            this.syncFileSearchState();
            this.syncCommandSearchState();
            this.promoteLeadingSlashCommandToken();
        },
        saveClipboardImage(file) {
            var reader = new FileReader();
            reader.onload = function () {
                EA_IMAGE_REFERENCE_COUNTER += 1;
                EABridge.saveClipboardImage(reader.result, 'Image' + EA_IMAGE_REFERENCE_COUNTER);
            };
            reader.onerror = function () {
                console.warn('[EasyAgent] Failed to read clipboard image');
            };
            reader.readAsDataURL(file);
        },
        syncFileSearchState() {
            var context = this.getMentionContext();
            if (!context) {
                this.closeFileSearch();
                return;
            }
            var mention = this.parseActiveMention(context.beforeCaret);
            if (!mention) {
                this.closeFileSearch();
                return;
            }
            this.activeMentionRange = mention.range;
            this.fileSearchQuery = mention.query;
            this.showFileSearch = true;
            this.requestFileCandidates(mention.query);
        },
        syncCommandSearchState() {
            if (this._commandJustInserted) {
                return;
            }
            var context = this.getMentionContext();
            if (!context) {
                this.closeCommandSearch();
                return;
            }
            var slash = this.parseActiveSlashCommand(context.beforeCaret);
            if (!slash) {
                this.closeCommandSearch();
                return;
            }
            this.activeSlashRange = slash.range;
            this.commandSearchQuery = slash.query;
            this.showCommandSearch = true;
            this.commandSearchResults = this.filterCommandCandidates(slash.query);
            this.activeCommandIndex = 0;
            this.$nextTick(function () {
                this.scrollActiveCommandIntoView();
            }.bind(this));
        },
        promoteLeadingSlashCommandToken() {
            var composer = this.$refs.composer;
            if (!composer || composer.querySelector(EA_COMMAND_SELECTOR)) {
                return;
            }
            var resolved = this.resolveLeadingSlashCommand(this.getComposerText());
            if (!resolved || !resolved.command) {
                return;
            }
            this.replaceSerializedRange(resolved.range.start, resolved.range.end, '');
            this.pendingInsertRange = this.getCurrentSelectionRange()
                || this.lastSelectionRange
                || this.createRangeAtComposerEnd();
            this.insertInlineTokenAtSelection(this.createCommandChip(resolved.command), false);
            this.closeCommandSearch();
        },
        parseActiveMention(beforeCaret) {
            var match = beforeCaret.match(/@([^\s@]*)$/);
            if (!match) return null;
            var token = '@' + (match[1] || '');
            var start = beforeCaret.length - token.length;
            return {
                query: match[1] || '',
                range: {
                    start: start,
                    end: beforeCaret.length
                }
            };
        },
        requestFileCandidates(query) {
            var requestId = 'fr-' + (++EA_FILE_SEARCH_REQUEST_COUNTER);
            this.pendingFileSearchRequestId = requestId;
            this.fileSearchLoading = true;
            if (this.fileSearchTimer) {
                clearTimeout(this.fileSearchTimer);
            }
            this.fileSearchTimer = setTimeout(function () {
                EABridge.searchFileReferences(query, 12, requestId);
            }, 90);
        },
        closeFileSearch() {
            this.showFileSearch = false;
            this.fileSearchQuery = '';
            this.fileSearchResults = [];
            this.fileSearchLoading = false;
            this.activeFileIndex = 0;
            this.activeMentionRange = null;
            this.pendingFileSearchRequestId = '';
            if (this.fileSearchTimer) {
                clearTimeout(this.fileSearchTimer);
                this.fileSearchTimer = null;
            }
        },
        moveActiveFile(delta) {
            if (this.fileSearchResults.length === 0) return;
            var next = this.activeFileIndex + delta;
            if (next < 0) next = this.fileSearchResults.length - 1;
            if (next >= this.fileSearchResults.length) next = 0;
            this.activeFileIndex = next;
        },
        selectFileCandidate(candidate) {
            if (!candidate || !candidate.path || !this.activeMentionRange) return;
            this.replaceSerializedRange(this.activeMentionRange.start, this.activeMentionRange.end, '');
            this.pendingInsertRange = this.getCurrentSelectionRange()
                || this.lastSelectionRange
                || this.createRangeAtComposerEnd();
            this.closeFileSearch();
            EABridge.resolveFileReference(candidate.path);
        },
        insertReferencesAtCaret(references) {
            var composer = this.$refs.composer;
            if (!composer || !references || references.length === 0) {
                return;
            }
            this.focusComposer();
            var range = this.pendingInsertRange ? this.pendingInsertRange.cloneRange() : this.getActiveRange();
            if (!range) {
                return;
            }
            range.deleteContents();
            var fragment = document.createDocumentFragment();
            var normalized = [];

            for (var i = 0; i < references.length; i++) {
                var reference = this.normalizeReference(references[i]);
                if (!reference) {
                    continue;
                }
                normalized.push(reference);
                this.referenceRegistry[reference.instanceId] = reference;
                fragment.appendChild(this.createReferenceChip(reference));
                fragment.appendChild(document.createTextNode(''));
            }
            if (normalized.length === 0) {
                return;
            }

            var lastNode = fragment.lastChild;
            range.insertNode(fragment);
            if (lastNode && lastNode.parentNode) {
                if (lastNode.nodeType === Node.TEXT_NODE) {
                    range.setStart(lastNode, 0);
                } else {
                    range.setStartAfter(lastNode);
                }
            }
            range.collapse(true);
            this.pendingInsertRange = null;
            this.setSelection(range);
            this.refreshComposerState();
        },
        buildSubmitText() {
            return this.getComposerText();
        },
        resetComposer() {
            var composer = this.$refs.composer;
            if (composer) {
                composer.innerHTML = '';
            }
            this.fileReferences = [];
            this.referenceRegistry = {};
            this.lastSelectionRange = null;
            this.pendingInsertRange = null;
            this.composerEmpty = true;
        },
        send() {
            var text = this.buildSubmitText();
            var plainText = text;
            this.fileReferences.forEach(function (reference) {
                if (!reference || !reference.inlineToken) {
                    return;
                }
                plainText = plainText.split(reference.inlineToken).join('');
            });
            if ((!plainText || !plainText.trim()) && this.fileReferences.length === 0) return;
            if (this.disabled) return;
            var slashCommand = this.resolveSubmittedSlashCommand(text);

            this.$emit('send', {
                text: text,
                fileReferences: this.fileReferences.slice(),
                slashCommand: slashCommand
            });
            this.resetComposer();
            this.closeFileSearch();
            this.closeCommandSearch();
        }
    }
});
