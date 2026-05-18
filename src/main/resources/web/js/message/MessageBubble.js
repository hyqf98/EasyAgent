/**
 * 消息气泡组件。
 * <p>
 * 渲染单条对话消息，根据 role 属性决定左右布局。
 * 支持：TEXT、THINKING、TOOL_USE、TODO_LIST、ERROR。
 * </p>
 *
 * @component message-bubble
 */
window.EARegisterComponent('message-bubble', 'MessageBubble', {
    props: {
        message: { type: Object, required: true },
        isLast: { type: Boolean, default: false },
        showRetry: { type: Boolean, default: false }
    },
    emits: ['retry', 'stop'],
    computed: {
        store() { return window.EAStore; },
        i18n() { void this.store.i18nVersion; return window.EAi18n; }
    },
    methods: {
        parseMetaLines(text) {
            var raw = (text || '').trim();
            if (!raw) {
                return null;
            }
            var normalized = raw
                .replace(/<system-reminder>/gi, '')
                .replace(/<\/system-reminder>/gi, '')
                .replace(/<permissions instructions>/gi, '')
                .replace(/<\/permissions instructions>/gi, '')
                .replace(/<collaboration_mode>/gi, '')
                .replace(/<\/collaboration_mode>/gi, '')
                .replace(/<skills_instructions>/gi, '')
                .replace(/<\/skills_instructions>/gi, '')
                .replace(/<environment_context>/gi, '')
                .replace(/<\/environment_context>/gi, '')
                .trim();
            var lines = normalized.split('\n').map(function (line) { return line.trim(); }).filter(Boolean);
            if (lines.length === 0) {
                return null;
            }

            var sectionKeywords = /(plugin|plugins|skill|skills|mcp|tool|tools|server|servers|context|mode|cwd|directory|workspace|provider|providers|model|approval|sandbox)/i;
            var sections = [];
            var summary = '';
            var currentSection = null;
            var hasMeta = false;

            function createSection(label) {
                currentSection = {
                    label: label.trim(),
                    value: '',
                    items: [],
                    itemMode: 'chip'
                };
                sections.push(currentSection);
                hasMeta = true;
                return currentSection;
            }

            function appendChipItems(section, value) {
                if (!section || !value) {
                    return;
                }
                value.split(/[，,、;|]/).map(function (item) { return item.trim(); }).filter(Boolean).forEach(function (item) {
                    section.items.push(item);
                });
            }

            function appendStructuredItem(section, line) {
                if (!section || !line) {
                    return false;
                }
                var named = line.match(/^[-*•]?\s*([^:]+):\s*(.+?)(?:\s+\(file:\s*([^)]+)\))?$/);
                if (!named) {
                    return false;
                }
                section.items.push({
                    name: named[1].trim(),
                    detail: named[2].trim(),
                    file: named[3] ? named[3].trim() : ''
                });
                section.itemMode = 'row';
                return true;
            }

            function firstItemLabel(section) {
                if (!section || !section.items || section.items.length === 0) {
                    return '';
                }
                var first = section.items[0];
                return typeof first === 'object' ? (first.name || first.detail || first.file || '') : first;
            }

            for (var i = 0; i < lines.length; i++) {
                var line = lines[i];
                var heading = line.match(/^(#{1,6}\s+)?([A-Za-z][A-Za-z0-9_ /-]{1,40})[:：]?\s*$/);
                if (heading && sectionKeywords.test(heading[2])) {
                    createSection(heading[2]);
                    continue;
                }

                var match = line.match(/^([A-Za-z][A-Za-z0-9_ /-]{1,40}):\s*(.*)$/);
                if (match && sectionKeywords.test(match[1])) {
                    var section = createSection(match[1]);
                    if (match[2]) {
                        if (!appendStructuredItem(section, match[1] + ': ' + match[2])) {
                            appendChipItems(section, match[2]);
                        }
                        if (!section.items.length) {
                            section.value = match[2].trim();
                        }
                        if (!summary) {
                            summary = section.label + ': ' + (firstItemLabel(section) || section.value);
                        }
                    }
                    continue;
                }

                var bullet = line.match(/^(?:[-*•]|\d+\.)\s+(.+)$/);
                if (bullet && currentSection) {
                    if (!appendStructuredItem(currentSection, bullet[1])) {
                        appendChipItems(currentSection, bullet[1]);
                    }
                    if (!summary) {
                        summary = currentSection.label + ': ' + firstItemLabel(currentSection);
                    }
                    continue;
                }

                if (currentSection) {
                    currentSection.value = currentSection.value ? (currentSection.value + '\n' + line) : line;
                    if (!summary) {
                        summary = currentSection.value.split('\n')[0];
                    }
                    continue;
                }

                if (!summary) {
                    summary = line;
                }
            }

            if (!hasMeta && !sectionKeywords.test(normalized)) {
                return null;
            }

            var category = 'context';
            if (/(plugin|plugins|mcp|server|servers)/i.test(normalized)) {
                category = 'plugin';
            } else if (/(skill|skills)/i.test(normalized)) {
                category = 'skill';
            } else if (/(tool|tools)/i.test(normalized)) {
                category = 'tool';
            } else if (/(system|context|workspace|cwd|sandbox|approval|model|provider)/i.test(normalized)) {
                category = 'context';
            }

            if (!summary && sections.length > 0) {
                var firstSection = sections[0];
                summary = firstSection.items.length > 0
                    ? firstSection.label + ': ' + firstItemLabel(firstSection)
                    : (firstSection.value ? firstSection.label + ': ' + firstSection.value.split('\n')[0] : firstSection.label);
            }

            return {
                summary: summary || lines[0],
                category: category,
                sections: sections,
                rawText: normalized
            };
        },
        toggleThinking(block) { block.collapsed = !block.collapsed; },
        toggleTool(block) { block.collapsed = !block.collapsed; },
        toggleSystemInfo(block) { block.collapsed = !block.collapsed; },
        renderMarkdown(text) { return EAMarkdown.render(text); },
        stripTaskList(text) {
            if (!text) return text;
            return text.replace(/---TASK_LIST_START---[\s\S]*?---TASK_LIST_END---/g, '').trim();
        },
        isUserMessage() { return this.message.role === 'USER'; },
        isReferenceBlock(b) { return b.type === 'REFERENCE' && !!b.reference; },
        isTextBlock(b) { return b.type === 'TEXT'; },
        isThinkingBlock(b) { return b.type === 'THINKING'; },
        isToolBlock(b) { return b.type === 'TOOL_USE'; },
        isErrorBlock(b) { return b.type === 'ERROR'; },
        isTodoBlock(b) { return b.type === 'TODO_LIST'; },
        isCompactBlock(b) { return b.type === 'COMPACT'; },
        compactLabel(block) { return block.completed ? 'COMPACTION' : 'COMPACTING'; },
        compactMeta(block) {
            if (!block || !block.preTokens) return '';
            var parts = [];
            if (block.trigger) parts.push(block.trigger === 'auto' ? 'auto' : 'manual');
            var preK = Math.round(block.preTokens / 1000);
            var postK = block.postTokens ? Math.round(block.postTokens / 1000) : 0;
            parts.push(preK + 'K → ' + postK + 'K tokens');
            if (block.durationMs) {
                var sec = (block.durationMs / 1000).toFixed(1);
                parts.push(sec + 's');
            }
            return '· ' + parts.join(' · ');
        },
        isSystemInfoBlock(b) {
            if (b && b.type === 'SYSTEM_INFO' && b.collapsed === undefined) {
                b.collapsed = true;
            }
            return b && b.type === 'SYSTEM_INFO';
        },
        isCliMetaBlock(b) {
            if (this.message.role === 'ASSISTANT' || this.message.role === 'TOOL_RESULT') {
                return false;
            }
            var info = this.isTextBlock(b) ? this.parseMetaLines(b.text) : null;
            if (info && b.collapsed === undefined) {
                b.collapsed = true;
            }
            return !!info;
        },
        isMetaItemObject(item) {
            return !!item && typeof item === 'object';
        },
        metaItemName(item) {
            if (!item) {
                return '';
            }
            return typeof item === 'object' ? (item.name || '') : String(item);
        },
        metaItemDetail(item) {
            if (!item) {
                return '';
            }
            return typeof item === 'object' ? (item.detail || '') : '';
        },
        metaItemFile(item) {
            if (!item || typeof item !== 'object') {
                return '';
            }
            return item.file || '';
        },
        metaInfoForBlock(block) {
            if (!block) {
                return null;
            }
            var info = block.type === 'SYSTEM_INFO'
                ? this.parseMetaLines(block.fullText || block.summary || '')
                : this.parseMetaLines(block.text || '');
            if (info && block.collapsed === undefined) {
                block.collapsed = true;
            }
            return info;
        },
        metaCategoryClass(info) {
            return info && info.category ? info.category : 'context';
        },
        metaBadge(info) {
            switch (info && info.category) {
                case 'plugin': return this.i18n.t('meta.plugin');
                case 'skill': return this.i18n.t('meta.skill');
                case 'tool': return this.i18n.t('meta.tooling');
                default: return this.i18n.t('meta.context');
            }
        },
        metaTitle(info) {
            var cli = this.store.cliLabel || 'CLI';
            switch (info && info.category) {
                case 'plugin': return cli + ' ' + this.i18n.t('meta.pluginTitle');
                case 'skill': return cli + ' ' + this.i18n.t('meta.skillTitle');
                case 'tool': return cli + ' ' + this.i18n.t('meta.toolTitle');
                default: return cli + ' ' + this.i18n.t('meta.contextTitle');
            }
        },
        referenceTypeClass(reference) {
            return reference && reference.referenceType === 'IMAGE' ? 'image-ref' : 'file-ref';
        },
        referenceIcon(reference) {
            return reference && reference.referenceType === 'IMAGE' ? 'IMG' : 'FILE';
        },
        referenceLabel(reference) {
            if (!reference) {
                return '';
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
        shouldShowStatus() {
            return this.message.role === 'ASSISTANT'
                && (this.message.streamState || this.message.finishReason || this.message.tokenUsage);
        },
        canStop() {
            return this.isLast && this.store.isStreaming
                && (this.message.streamState === 'generating' || this.message.streamState === 'retrying');
        },
        roleLabel(role) {
            switch (role) {
                case 'USER': return this.i18n.t('role.user');
                case 'ASSISTANT': return this.i18n.t('role.assistant');
                case 'DEVELOPER': return 'Developer';
                default: return role;
            }
        },
        roleEmoji(role) { return role === 'USER' ? '👤' : '🤖'; }
    }
});
