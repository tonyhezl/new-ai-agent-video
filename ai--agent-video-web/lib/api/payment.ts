import { http } from "./client";

// ========== 类型定义 ==========

export interface RechargePackage {
  id: number;
  name: string;
  amountCny: number;
  creditAmount: number;
  bonusCredit: number;
  sort: number;
  status: number;
}

export interface RechargeOrder {
  id: number;
  orderNo: string;
  userId: number;
  amountCny: number;
  creditAmount: number;
  bonusCredit: number;
  payChannel: string;
  status: number;
  payUrl?: string;
  qrcodeUrl?: string;
  createTime?: string;
  payTime?: string;
}

// ========== API 方法 ==========

export async function listRechargePackages(): Promise<RechargePackage[]> {
  return http.get<never, RechargePackage[]>("/api/payment/recharge-packages");
}

export async function createRechargeOrder(
  amountCny: number,
  payChannel: string
): Promise<RechargeOrder> {
  return http.post<never, RechargeOrder>("/api/payment/recharge-orders", {
    amountCny,
    payChannel,
  });
}

export async function getOrderStatus(orderNo: string): Promise<RechargeOrder> {
  return http.get<never, RechargeOrder>(
    `/api/payment/recharge-orders/${orderNo}`
  );
}

export async function pollOrderStatus(
  orderNo: string,
  intervalMs = 2000,
  timeoutMs = 300000
): Promise<RechargeOrder> {
  const start = Date.now();
  return new Promise((resolve, reject) => {
    const timer = setInterval(async () => {
      if (Date.now() - start > timeoutMs) {
        clearInterval(timer);
        reject(new Error("支付超时"));
        return;
      }
      try {
        const order = await getOrderStatus(orderNo);
        if (order.status === 1) {
          clearInterval(timer);
          resolve(order);
        }
      } catch (err) {
        clearInterval(timer);
        reject(err);
      }
    }, intervalMs);
  });
}
