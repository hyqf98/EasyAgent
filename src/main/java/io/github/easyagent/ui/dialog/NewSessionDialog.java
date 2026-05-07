package io.github.easyagent.ui.dialog;

import com.intellij.openapi.ui.DialogWrapper;
import io.github.easyagent.enums.CLIType;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

/**
 * 新建会话对话框。
 * <p>
 * 允许用户选择 CLI 类型（Claude / OpenCode / Codex）来创建新的对话会话。
 * 对话框包含一个下拉选择器，默认选中当前活跃的 CLI 类型。
 * </p>
 *
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
public class NewSessionDialog extends DialogWrapper {

    /** CLI 类型下拉选择器。 */
    private final JComboBox<CLIType> cliTypeCombo;

    /** 用户选中的 CLI 类型。 */
    @Getter
    private CLIType selectedCLI;

    /**
     * 构造新建会话对话框。
     */
    public NewSessionDialog() {
        super(true);
        this.setTitle("New Chat Session");
        this.cliTypeCombo = new JComboBox<>(CLIType.values());
        this.cliTypeCombo.setSelectedItem(CLIType.CLAUDE);
        this.cliTypeCombo.setRenderer(new CLICellRenderer());
        this.init();
    }

    /**
     * 创建对话框中央面板。
     *
     * @return 包含 CLI 类型选择器的面板
     */
    @Nullable
    @Override
    protected JComponent createCenterPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(8, 8, 8, 8);
        gbc.anchor = GridBagConstraints.WEST;

        JLabel label = new JLabel("Select CLI Type:");
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 13f));
        gbc.gridx = 0;
        gbc.gridy = 0;
        panel.add(label, gbc);

        this.cliTypeCombo.setPreferredSize(new Dimension(220, 30));
        gbc.gridx = 1;
        gbc.gridy = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        panel.add(this.cliTypeCombo, gbc);

        return panel;
    }

    /**
     * 确认按钮点击时保存选中的 CLI 类型。
     */
    @Override
    protected void doOKAction() {
        this.selectedCLI = (CLIType) this.cliTypeCombo.getSelectedItem();
        super.doOKAction();
    }

    /**
     * CLI 类型下拉列表渲染器。
     *
     * @author haijun
     * @date 2026/4/30
     * @since 1.0.0
     */
    private static class CLICellRenderer extends JLabel implements ListCellRenderer<CLIType> {

        /**
         * 构造渲染器。
         */
        CLICellRenderer() {
            this.setOpaque(true);
        }

        /**
         * 渲染下拉列表单元格。
         *
         * @param list         下拉列表
         * @param value        当前 CLI 类型
         * @param index        索引
         * @param isSelected   是否选中
         * @param cellHasFocus 是否获得焦点
         * @return 渲染后的组件
         */
        @Override
        public JComponent getListCellRendererComponent(
                JList<? extends CLIType> list, CLIType value,
                int index, boolean isSelected, boolean cellHasFocus) {
            this.setText(value.getName());
            if (isSelected) {
                this.setBackground(list.getSelectionBackground());
                this.setForeground(list.getSelectionForeground());
            } else {
                this.setBackground(list.getBackground());
                this.setForeground(list.getForeground());
            }
            return this;
        }
    }
}
