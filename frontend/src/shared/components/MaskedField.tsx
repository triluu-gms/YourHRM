import { useState } from 'react';
import { Button, Space, Tooltip, Typography } from 'antd';
import { EyeInvisibleOutlined, EyeOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';

/**
 * Field-level masking (BR-CORE-040): the API already returns a masked value for users without the clearance;
 * when an `onReveal` is given (a purpose-logged unmask call), the user can fetch the clear value on demand.
 */
export function MaskedField({
  value,
  onReveal,
}: {
  value: string | null | undefined;
  onReveal?: () => Promise<string>;
}) {
  const { t } = useTranslation();
  const [revealed, setRevealed] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  if (value === null || value === undefined || value === '') return <Typography.Text type="secondary">—</Typography.Text>;
  const shown = revealed ?? value;
  return (
    <Space size={4}>
      <Typography.Text style={{ fontFamily: 'monospace' }}>{shown}</Typography.Text>
      {onReveal ? (
        <Tooltip title={revealed ? t('common.hideValue') : t('common.showValue')}>
          <Button
            type="text"
            size="small"
            loading={loading}
            icon={revealed ? <EyeInvisibleOutlined /> : <EyeOutlined />}
            onClick={async () => {
              if (revealed) {
                setRevealed(null);
                return;
              }
              setLoading(true);
              try {
                setRevealed(await onReveal());
              } finally {
                setLoading(false);
              }
            }}
          />
        </Tooltip>
      ) : null}
    </Space>
  );
}
