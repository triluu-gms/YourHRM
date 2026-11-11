import { Alert, Button, Card, Form, Input, Space, Typography } from 'antd';
import { LockOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useSalaryPurpose } from './queries';
import './i18n';

/** Asks for the purpose of viewing salary data (US-PPL-045) before any salary read is sent. */
export function SalaryPurposeForm() {
  const { t: te } = useTranslation('people-employment');
  const [, setPurpose] = useSalaryPurpose();
  const [form] = Form.useForm<{ purpose: string }>();
  return (
    <Card size="small" title={<Space><LockOutlined />{te('salary.purposeTitle')}</Space>}>
      <Typography.Paragraph type="secondary">{te('salary.purposeIntro')}</Typography.Paragraph>
      <Form
        form={form}
        layout="inline"
        onFinish={(v) => setPurpose(v.purpose)}
        style={{ rowGap: 8 }}
      >
        <Form.Item
          name="purpose"
          label={te('salary.purpose')}
          rules={[{ required: true, whitespace: true, message: te('salary.purposeRequired') }, { max: 500 }]}
          style={{ minWidth: 360, flex: 1 }}
        >
          <Input autoComplete="off" />
        </Form.Item>
        <Form.Item>
          <Button type="primary" htmlType="submit">
            {te('salary.purposeSubmit')}
          </Button>
        </Form.Item>
      </Form>
    </Card>
  );
}

/** Small banner with the purpose in force and a way to change it. */
export function SalaryPurposeBanner() {
  const { t: te } = useTranslation('people-employment');
  const [purpose, setPurpose] = useSalaryPurpose();
  if (!purpose) return null;
  return (
    <Alert
      type="info"
      showIcon
      icon={<LockOutlined />}
      message={te('salary.purposeCurrent', { purpose })}
      action={
        <Button size="small" type="link" onClick={() => setPurpose(undefined)}>
          {te('salary.changePurpose')}
        </Button>
      }
    />
  );
}
