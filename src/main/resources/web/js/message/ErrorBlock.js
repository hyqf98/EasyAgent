/**
 * 错误提示块组件。
 * <p>
 * 显示红色左边框的错误信息。
 * </p>
 *
 * @component error-block
 */
window.EARegisterComponent('error-block', 'ErrorBlock', {
    props: {
        message: { type: String, default: '' }
    }
});
