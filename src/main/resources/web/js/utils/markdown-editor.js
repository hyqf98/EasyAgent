/**
 * Typora-style inline Markdown editor.
 * <p>
 * Uses a single contenteditable container. Markdown is rendered via EAMarkdown.
 * - Type markdown syntax (e.g. "## Title") then press Space to trigger rendering.
 * - Click on a rendered block to reveal its source syntax.
 * - Press Backspace at the start of a rendered block to revert to source.
 * - Press Enter to create a new paragraph and re-render.
 * </p>
 *
 * @namespace EAMarkdownEditor
 */
window.EAMarkdownEditor = (function () {

    var HEADING_RE = /^(\s{0,3})(#{1,6})\s+(.*)$/;

    var BLOCK_TAGS = { h1: 1, h2: 1, h3: 1, h4: 1, h5: 1, h6: 1, blockquote: 1 };

    function Editor(container, initialMarkdown) {
        this.container = container;
        this.raw = initialMarkdown || '';
        this.container._eaEditor = this;
        this.container.classList.add('md-editor');
        this._bound = false;
        this._bindEvents();
        this._fullRender();
    }

    Editor.prototype._fullRender = function () {
        var html = this._toHtml(this.raw);
        this.container.innerHTML = html;
        this.container.setAttribute('contenteditable', 'true');
        this.container.spellcheck = false;
        this._markRenderedBlocks();
    };

    Editor.prototype._toHtml = function (text) {
        if (!text) return '<p><br></p>';
        if (window.EAMarkdown && window.EAMarkdown.render) {
            return window.EAMarkdown.render(text);
        }
        if (typeof marked !== 'undefined') {
            return marked.parse(text);
        }
        return text.replace(/\n/g, '<br>');
    };

    Editor.prototype._markRenderedBlocks = function () {
        var children = this.container.children;
        for (var i = 0; i < children.length; i++) {
            var el = children[i];
            var tag = el.tagName.toLowerCase();
            if (BLOCK_TAGS[tag] && !el.getAttribute('data-md-source')) {
                el.setAttribute('data-md-source', this._buildSource(el));
            }
        }
    };

    Editor.prototype._buildSource = function (el) {
        var tag = el.tagName.toLowerCase();
        var m = tag.match(/^h([1-6])$/);
        if (m) {
            var hashes = '';
            for (var i = 0; i < parseInt(m[1]); i++) hashes += '#';
            return hashes + ' ' + (el.textContent || '');
        }
        if (tag === 'blockquote') {
            return '> ' + (el.textContent || '');
        }
        return '';
    };

    Editor.prototype._bindEvents = function () {
        if (this._bound) return;
        this._bound = true;
        var self = this;
        this._onKeydown = function (e) { self._handleKeydown(e); };
        this._onInput = function () { self._handleInput(); };
        this._onClick = function (e) { self._handleClick(e); };
        this.container.addEventListener('keydown', this._onKeydown);
        this.container.addEventListener('input', this._onInput);
        this.container.addEventListener('click', this._onClick);
    };

    Editor.prototype.destroy = function () {
        if (this._bound) {
            this.container.removeEventListener('keydown', this._onKeydown);
            this.container.removeEventListener('input', this._onInput);
            this.container.removeEventListener('click', this._onClick);
            this._bound = false;
        }
        this.container._eaEditor = null;
        this.container.innerHTML = '';
        this.container.classList.remove('md-editor');
        this.container.removeAttribute('contenteditable');
    };

    Editor.prototype.setContent = function (markdown) {
        this.raw = markdown || '';
        this._fullRender();
    };

    Editor.prototype.getMarkdown = function () {
        return this.raw;
    };

    Editor.prototype._syncRawFromDOM = function () {
        this.raw = this._domToMarkdown(this.container);
    };

    Editor.prototype._domToMarkdown = function (el) {
        var parts = [];
        for (var i = 0; i < el.childNodes.length; i++) {
            var node = el.childNodes[i];
            if (node.nodeType === Node.TEXT_NODE) {
                var t = node.textContent;
                if (t) parts.push(t);
            } else if (node.nodeType === Node.ELEMENT_NODE) {
                var tag = node.tagName.toLowerCase();
                var inner = this._domToMarkdown(node);
                if (tag === 'h1') parts.push('# ' + inner);
                else if (tag === 'h2') parts.push('## ' + inner);
                else if (tag === 'h3') parts.push('### ' + inner);
                else if (tag === 'h4') parts.push('#### ' + inner);
                else if (tag === 'h5') parts.push('##### ' + inner);
                else if (tag === 'h6') parts.push('###### ' + inner);
                else if (tag === 'p') parts.push(inner);
                else if (tag === 'br') parts.push('\n');
                else if (tag === 'blockquote') parts.push('> ' + inner);
                else if (tag === 'ul' || tag === 'ol') parts.push(inner);
                else if (tag === 'li') parts.push('- ' + inner);
                else if (tag === 'strong' || tag === 'b') parts.push('**' + inner + '**');
                else if (tag === 'em' || tag === 'i') parts.push('*' + inner + '*');
                else if (tag === 'code') parts.push('`' + inner + '`');
                else if (tag === 'pre') parts.push('```\n' + inner + '\n```');
                else if (tag === 'a') parts.push('[' + inner + '](' + (node.getAttribute('href') || '') + ')');
                else if (tag === 'hr') parts.push('---');
                else if (tag === 'div') parts.push(inner);
                else parts.push(inner);
            }
        }
        return parts.join('');
    };

    Editor.prototype._handleKeydown = function (e) {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            this._handleEnter();
            return;
        }

        if (e.key === 'Backspace') {
            this._handleBackspace(e);
            return;
        }

        if (e.key === ' ' || e.key === 'Spacebar') {
            this._handleSpace(e);
        }
    };

    Editor.prototype._handleEnter = function () {
        this._syncRawFromDOM();
        this.raw += '\n';
        this._fullRender();
        this._placeCursorAtEnd();
    };

    Editor.prototype._handleBackspace = function (e) {
        var sel = window.getSelection();
        if (!sel || !sel.rangeCount || !sel.isCollapsed) return;

        var node = sel.anchorNode;
        var offset = sel.anchorOffset;

        var rendered = this._findRenderedBlock(node);
        if (!rendered) return;

        if (!this._isAtBlockStart(rendered, node, offset)) return;

        var source = rendered.getAttribute('data-md-source') || this._buildSource(rendered);
        if (!source) return;

        e.preventDefault();
        var textNode = document.createTextNode(source);
        rendered.parentNode.replaceChild(textNode, rendered);
        var range = document.createRange();
        range.setStart(textNode, 0);
        range.collapse(true);
        sel.removeAllRanges();
        sel.addRange(range);
        this._syncRawFromDOM();
    };

    Editor.prototype._handleSpace = function (e) {
        var sel = window.getSelection();
        if (!sel || !sel.rangeCount) return;
        var node = sel.anchorNode;
        if (node.nodeType !== Node.TEXT_NODE) return;

        var text = node.textContent;
        var offset = sel.anchorOffset;

        var textBefore = text.substring(0, offset);
        if (!HEADING_RE.test(textBefore)) return;

        var parent = node.parentNode;
        if (parent !== this.container && parent.tagName && parent.tagName.toLowerCase() !== 'p') return;

        e.preventDefault();
        var match = textBefore.match(HEADING_RE);
        var headingLevel = match[2].length;
        var headingText = match[3];
        var rest = text.substring(offset);

        var h = document.createElement('h' + headingLevel);
        h.textContent = headingText;
        h.setAttribute('data-md-source', match[0]);

        if (parent.tagName && parent.tagName.toLowerCase() === 'p') {
            if (rest) {
                var restText = document.createTextNode(rest);
                parent.parentNode.insertBefore(h, parent);
                parent.textContent = rest;
            } else {
                parent.parentNode.replaceChild(h, parent);
            }
        } else {
            if (rest) {
                node.textContent = rest;
                node.parentNode.insertBefore(h, node);
            } else {
                node.parentNode.replaceChild(h, node);
            }
        }

        var range = document.createRange();
        range.selectNodeContents(h);
        range.collapse(false);
        sel.removeAllRanges();
        sel.addRange(range);
        this._syncRawFromDOM();
    };

    Editor.prototype._handleInput = function () {
        this._syncRawFromDOM();
    };

    Editor.prototype._handleClick = function (e) {
        var target = e.target;
        var rendered = this._findRenderedBlock(target);
        if (!rendered) return;
        var source = rendered.getAttribute('data-md-source');
        if (!source) return;

        rendered.removeAttribute('data-md-source');
        var textNode = document.createTextNode(source);
        rendered.parentNode.replaceChild(textNode, rendered);
        var range = document.createRange();
        range.selectNodeContents(textNode);
        range.collapse(false);
        var sel = window.getSelection();
        sel.removeAllRanges();
        sel.addRange(range);
        this._syncRawFromDOM();
    };

    Editor.prototype._findRenderedBlock = function (node) {
        var current = node;
        while (current && current !== this.container) {
            if (current.nodeType === Node.ELEMENT_NODE) {
                var tag = current.tagName.toLowerCase();
                if (BLOCK_TAGS[tag]) return current;
            }
            current = current.parentNode;
        }
        return null;
    };

    Editor.prototype._isAtBlockStart = function (block, anchorNode, anchorOffset) {
        if (anchorNode === block) {
            return anchorOffset === 0;
        }
        if (anchorNode.nodeType === Node.TEXT_NODE && anchorOffset === 0) {
            var parent = anchorNode.parentNode;
            while (parent && parent !== block) {
                var prev = this._getPreviousSibling(parent);
                if (prev) return false;
                parent = parent.parentNode;
            }
            return parent === block;
        }
        return false;
    };

    Editor.prototype._getPreviousSibling = function (node) {
        var p = node.previousSibling;
        while (p) {
            if (p.nodeType === Node.TEXT_NODE) {
                if (p.textContent.length > 0) return p;
            } else {
                return p;
            }
            p = p.previousSibling;
        }
        return null;
    };

    Editor.prototype._placeCursorAtEnd = function () {
        var range = document.createRange();
        range.selectNodeContents(this.container);
        range.collapse(false);
        var sel = window.getSelection();
        sel.removeAllRanges();
        sel.addRange(range);
    };

    return {
        create: function (container, initialMarkdown) {
            return new Editor(container, initialMarkdown);
        },
        destroy: function (container) {
            var editor = container._eaEditor;
            if (editor) editor.destroy();
        },
        setContent: function (state, markdown) {
            if (state && state.setContent) state.setContent(markdown);
        },
        getMarkdown: function (state) {
            if (state && state.getMarkdown) return state.getMarkdown();
            return '';
        }
    };
})();
