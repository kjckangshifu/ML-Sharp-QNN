/**
 * unproject.c — NDC -> metric 转换
 * unproject.c — NDC to metric conversion
 */
#include "unproject.h"
#include <string.h>
#include <math.h>
#include <stdlib.h>

void unproject_simple(const Gaussians3DFlat *g, Gaussians3DFlat *out,
                       float f_px_x, float f_px_y, int img_w, int img_h,
                       float cx, float cy)
{
    if (out != g) {
        *out = *g;
    }
    int N = g->num_points;
    /* M4 = inv(ndc4 @ intr4 @ extr4), 取 [:3] 得 3x4
       ndc4 = [[2/W,0,-1,0],[0,2/H,-1,0],[0,0,1,0],[0,0,0,1]]
       intr4 = [[fx,0,cx,0],[0,fy,cy,0],[0,0,1,0],[0,0,0,1]]
       ndc4@intr4 = [[2fx/W,0,2cx/W-1,0],[0,2fy/H,2cy/H-1,0],[0,0,1,0],[0,0,0,1]]
       Inverse (3x3 part): [[W/2fx, 0, (1-2cx/W)W/2fx],[0, H/2fy, (1-2cy/H)H/2fy],[0,0,1]]
       mean_metric = mean @ T[:3,:3].T + T[:,3]
                  x' = x*W/(2fx) + z*tx,  y' = y*H/(2fy) + z*ty,  z' = z
       (T[:,3]=0 when extrinsics are identity) */
    float inv_2f_W = (float)img_w / (2.0f * f_px_x);
    float inv_2f_H = (float)img_h / (2.0f * f_px_y);
    float tx = (1.0f - 2.0f * cx / (float)img_w) * inv_2f_W;
    float ty = (1.0f - 2.0f * cy / (float)img_h) * inv_2f_H;

    for (int i = 0; i < N; i++) {
        float z = g->mean_vectors[2*N+i];
        out->mean_vectors[0*N+i] = g->mean_vectors[0*N+i] * inv_2f_W + z * tx;
        out->mean_vectors[1*N+i] = g->mean_vectors[1*N+i] * inv_2f_H + z * ty;
        /* z 不变 */
        /* z unchanged */
        out->singular_values[0*N+i] = g->singular_values[0*N+i] * inv_2f_W;
        out->singular_values[1*N+i] = g->singular_values[1*N+i] * inv_2f_H;
        /* quaternions 不变 */
        /* quaternions unchanged */
    }
}

/* ── 全量 SVD 版本 (3x3 SVD 简化) ── */
/* ── Full SVD version (simplified 3x3 SVD) ── */

/* 3x3 矩阵乘法 C = A * B, 行优先 */
/* 3x3 matrix multiply C = A * B, row-major */
static void mat3_mul(const float A[9], const float B[9], float C[9])
{
    for (int i = 0; i < 3; i++)
        for (int j = 0; j < 3; j++) {
            float s = 0;
            for (int k = 0; k < 3; k++)
                s += A[i*3+k] * B[k*3+j];
            C[i*3+j] = s;
        }
}

/* 3x3 转置 */
/* 3x3 transpose */
static void mat3_transpose(const float A[9], float AT[9])
{
    for (int i = 0; i < 3; i++)
        for (int j = 0; j < 3; j++)
            AT[i*3+j] = A[j*3+i];
}

/* quaternion -> 3x3 旋转矩阵 (先归一化, 与 torch rotation_matrices_from_quaternions 一致) */
/* quaternion to 3x3 rotation matrix (normalized first, matching torch rotation_matrices_from_quaternions) */
static void quat_to_rot(const float q[4], float R[9])
{
    float n = sqrtf(q[0]*q[0] + q[1]*q[1] + q[2]*q[2] + q[3]*q[3]);
    if (n < 1e-12f) n = 1.0f;
    float w = q[0]/n, x = q[1]/n, y = q[2]/n, z = q[3]/n;
    float w2 = w*w, x2 = x*x, y2 = y*y, z2 = z*z;
    R[0] = w2 + x2 - y2 - z2; R[1] = 2*(x*y - w*z);   R[2] = 2*(x*z + w*y);
    R[3] = 2*(x*y + w*z);   R[4] = w2 - x2 + y2 - z2; R[5] = 2*(y*z - w*x);
    R[6] = 2*(x*z - w*y);   R[7] = 2*(y*z + w*x);   R[8] = w2 - x2 - y2 + z2;
}

/* Jacobi SVD for 3x3 real matrix, double 精度 (行优先)
   Jacobi SVD for 3x3 real matrices, double precision (row-major)
   与 torch 的 decompose_covariance_matrices (fp64 SVD) 保持一致
   Matches torch's decompose_covariance_matrices (fp64 SVD) */
static int svd3x3(const double A[9],
                  double U[9], double S[3], double V[9])
{
    double a[9];
    memcpy(a, A, 9*sizeof(double));
    /* 初始 U=I, V=I */
    /* Initialize U=I, V=I */
    double u[9] = {1,0,0,0,1,0,0,0,1};
    double v[9] = {1,0,0,0,1,0,0,0,1};

    /* 单向 Jacobi 旋转: A' = J^T A J, J = [[c,s],[-s,c]] 于 (p,q) 平面,
       One-sided Jacobi rotation: A' = J^T A J with J = [[c,s],[-s,c]] in the (p,q) plane,
       theta = 0.5*atan2(2*apq, aqq-app) 使 A'[p,q] = 0 */
    for (int iter = 0; iter < 100; iter++) {
        double max_off = 0;
        int p = 0, q = 0;
        for (int i = 0; i < 3; i++)
            for (int j = i+1; j < 3; j++) {
                double off = fabs(a[i*3+j]);
                if (off > max_off) { max_off = off; p = i; q = j; }
            }
        if (max_off < 1e-14) break;

        double apq = a[p*3+q], app = a[p*3+p], aqq = a[q*3+q];
        double theta = 0.5 * atan2(2*apq, aqq - app);
        double c = cos(theta), s = sin(theta);

        /* 行旋转 (用原始 a): B = J^T A */
        /* Row rotation (from the original a): B = J^T A */
        double tmp[9];
        memcpy(tmp, a, 9*sizeof(double));
        for (int c2 = 0; c2 < 3; c2++) {
            double apc = a[p*3+c2], aqc = a[q*3+c2];
            tmp[p*3+c2] = c*apc - s*aqc;
            tmp[q*3+c2] = s*apc + c*aqc;
        }
        /* 列旋转: A' = B J, B = J^T A (行旋转后的完整矩阵),
           Column rotation: A' = B J, B = J^T A (the full matrix after row rotation);
           非 p,q 列元素 = B 对应元素, 列 p,q 由 B 旋转得到
           non-(p,q) columns come from B directly, columns p,q are rotated from B */
        memcpy(a, tmp, 9*sizeof(double));
        for (int r = 0; r < 3; r++) {
            double b_rp = tmp[r*3+p], b_rq = tmp[r*3+q];
            a[r*3+p] = c*b_rp - s*b_rq;
            a[r*3+q] = s*b_rp + c*b_rq;
        }

        /* U *= J (列旋转) */
        /* U *= J (column rotation) */
        for (int r = 0; r < 3; r++) {
            double up = u[r*3+p], uq = u[r*3+q];
            u[r*3+p] = c*up - s*uq;
            u[r*3+q] = s*up + c*uq;
        }
        /* V *= J (列旋转) */
        /* V *= J (column rotation) */
        for (int r = 0; r < 3; r++) {
            double vp = v[r*3+p], vq = v[r*3+q];
            v[r*3+p] = c*vp - s*vq;
            v[r*3+q] = s*vp + c*vq;
        }
    }

    /* 提取奇异值并按降序排列 (与 torch.linalg.svd 一致), U 列同步交换 */
    /* Extract singular values and sort descending (matching torch.linalg.svd); U columns are permuted along */
    S[0] = fabs(a[0*3+0]); S[1] = fabs(a[1*3+1]); S[2] = fabs(a[2*3+2]);
    {
        /* 简单插入排序: 3 个值 */
        /* Simple insertion sort: 3 values */
        int order[3] = {0, 1, 2};
        for (int i = 1; i < 3; i++) {
            double key = S[i];
            int j = i - 1;
            while (j >= 0 && S[order[j]] < key) {
                order[j+1] = order[j];
                j--;
            }
            order[j+1] = i;
        }
        double Ss[3] = {S[0], S[1], S[2]};
        double us[9];
        for (int i = 0; i < 3; i++) {
            S[i] = Ss[order[i]];
            for (int r = 0; r < 3; r++)
                us[r*3+i] = u[r*3+order[i]];
        }
        memcpy(u, us, 9*sizeof(double));
    }

    /* 列符号归一化 (与 verify 脚本一致): 每列绝对值最大的元素取正 */
    /* Column sign normalization (consistent with the verify script): the largest-magnitude element of each column is made positive */
    for (int c = 0; c < 3; c++) {
        int imax = 0;
        for (int r = 1; r < 3; r++)
            if (fabs(u[r*3+c]) > fabs(u[imax*3+c])) imax = r;
        if (u[imax*3+c] < 0)
            for (int r = 0; r < 3; r++) u[r*3+c] *= -1;
    }

    /* 处理 reflection: 确保 det(U) > 0 (翻转最后一列) */
    /* Handle reflection: enforce det(U) > 0 (flip the last column) */
    double det_u = u[0]*u[4]*u[8] + u[1]*u[5]*u[6] + u[2]*u[3]*u[7]
                 - u[2]*u[4]*u[6] - u[1]*u[3]*u[8] - u[0]*u[5]*u[7];
    if (det_u < 0) {
        for (int i = 0; i < 3; i++) u[i*3+2] *= -1;
    }

    memcpy(U, u, 9*sizeof(double));
    memcpy(V, v, 9*sizeof(double));
    return 0;
}

void unproject_full(const Gaussians3DFlat *g,
                     const float transform[12],
                     Gaussians3DFlat *out)
{
    int N = g->num_points;
    if (out != g) {
        out->mean_vectors = (float*)malloc(3*N*sizeof(float));
        out->singular_values = (float*)malloc(3*N*sizeof(float));
        out->quaternions = (float*)malloc(4*N*sizeof(float));
        out->colors = g->colors;
        out->opacities = g->opacities;
        out->num_points = N;
    }

    float R[9] = {transform[0], transform[1], transform[2],
                  transform[4], transform[5], transform[6],
                  transform[8], transform[9], transform[10]};
    float t[3] = {transform[3], transform[7], transform[11]};
    float RT[9];
    mat3_transpose(R, RT);

    for (int i = 0; i < N; i++) {
        /* mean = mean @ R^T + t */
        float mx = g->mean_vectors[0*N+i];
        float my = g->mean_vectors[1*N+i];
        float mz = g->mean_vectors[2*N+i];
        out->mean_vectors[0*N+i] = mx*RT[0] + my*RT[3] + mz*RT[6] + t[0];
        out->mean_vectors[1*N+i] = mx*RT[1] + my*RT[4] + mz*RT[7] + t[1];
        out->mean_vectors[2*N+i] = mx*RT[2] + my*RT[5] + mz*RT[8] + t[2];

        /* compose covariance: R0 @ diag(sv)^2 @ R0^T (double 精度, 同 torch) */
        /* compose covariance: R0 @ diag(sv)^2 @ R0^T (double precision, as in torch) */
        double svd_sv[3] = {(double)g->singular_values[0*N+i],
                            (double)g->singular_values[1*N+i],
                            (double)g->singular_values[2*N+i]};
        float q[4]  = {g->quaternions[0*N+i], g->quaternions[1*N+i],
                       g->quaternions[2*N+i], g->quaternions[3*N+i]};

        /* 从 quat 得到旋转矩阵 R0 */
        /* Get the rotation matrix R0 from the quaternion */
        float R0f[9];
        quat_to_rot(q, R0f);
        double R0[9];
        for (int k = 0; k < 9; k++) R0[k] = (double)R0f[k];

        /* 协方差 = R0 @ diag(sv)^2 @ R0^T */
        /* covariance = R0 @ diag(sv)^2 @ R0^T */
        double tmp[9], cov[9], cov_t[9];
        for (int k = 0; k < 3; k++)
            for (int j = 0; j < 3; j++)
                tmp[k*3+j] = R0[k*3+j] * (svd_sv[j]*svd_sv[j]);
        for (int k = 0; k < 3; k++)
            for (int j = 0; j < 3; j++) {
                double s = 0;
                for (int l = 0; l < 3; l++)
                    s += tmp[k*3+l] * R0[j*3+l];
                cov[k*3+j] = s;
            }

        /* 变换: R @ cov @ R^T */
        /* Transform: R @ cov @ R^T */
        for (int k = 0; k < 3; k++)
            for (int j = 0; j < 3; j++) {
                double s = 0;
                for (int l = 0; l < 3; l++)
                    s += R[k*3+l] * cov[l*3+j];
                tmp[k*3+j] = s;
            }
        for (int k = 0; k < 3; k++)
            for (int j = 0; j < 3; j++) {
                double s = 0;
                for (int l = 0; l < 3; l++)
                    s += tmp[k*3+l] * RT[j*3+l];
                cov_t[k*3+j] = s;
            }

        /* SVD 分解 cov_t → U, S, V^T */
        /* SVD of cov_t -> U, S, V^T */
        double U[9], V[9], S[3];
        svd3x3(cov_t, U, S, V);

        out->singular_values[0*N+i] = (float)sqrt(S[0]);
        out->singular_values[1*N+i] = (float)sqrt(S[1]);
        out->singular_values[2*N+i] = (float)sqrt(S[2]);

        /* U → quaternion (double), 与 scipy Rotation.from_matrix (torch 用) 一致:
           U -> quaternion (double), consistent with scipy Rotation.from_matrix (used by torch):
           判定: choice = argmax(r00, r11, r22, trace) (同值取小索引)
           Choice: argmax(r00, r11, r22, trace) (ties pick the smaller index)
           公式 (行优先, U[0]=r00, U[1]=r01, U[2]=r02, U[3]=r10, U[4]=r11,
           Formulas (row-major, U[0]=r00, U[1]=r01, U[2]=r02, U[3]=r10, U[4]=r11,
                 U[5]=r12, U[6]=r20, U[7]=r21, U[8]=r22):
             trace 分支: w=1+tr, x=r21-r12, y=r02-r20, z=r10-r01
             i 分支:     q_i=1-tr+2r_ii, q_j=r_ji+r_ij, q_k=r_ki+r_ik, w=r_kj-r_jk
           归一化后等价于下面公式 (s = 2*sqrt(...))
           After normalization these are equivalent to the formulas below (s = 2*sqrt(...)) */
        double tr = U[0] + U[4] + U[8];
        double m00 = U[0], m11 = U[4], m22 = U[8];
        if (tr >= m00 && tr >= m11 && tr >= m22) {
            double s = sqrt(tr + 1.0) * 2.0;
            out->quaternions[0*N+i] = (float)(0.25 * s);
            out->quaternions[1*N+i] = (float)((U[7] - U[5]) / s);
            out->quaternions[2*N+i] = (float)((U[2] - U[6]) / s);
            out->quaternions[3*N+i] = (float)((U[3] - U[1]) / s);
        } else if (m00 >= m11 && m00 >= m22) {
            double s = sqrt(1.0 + m00 - m11 - m22) * 2.0;
            out->quaternions[0*N+i] = (float)((U[7] - U[5]) / s);
            out->quaternions[1*N+i] = (float)(0.25 * s);
            out->quaternions[2*N+i] = (float)((U[1] + U[3]) / s);
            out->quaternions[3*N+i] = (float)((U[6] + U[2]) / s);
        } else if (m11 >= m22) {
            double s = sqrt(1.0 + m11 - m00 - m22) * 2.0;
            out->quaternions[0*N+i] = (float)((U[2] - U[6]) / s);
            out->quaternions[1*N+i] = (float)((U[1] + U[3]) / s);
            out->quaternions[2*N+i] = (float)(0.25 * s);
            out->quaternions[3*N+i] = (float)((U[5] + U[7]) / s);
        } else {
            double s = sqrt(1.0 + m22 - m00 - m11) * 2.0;
            out->quaternions[0*N+i] = (float)((U[3] - U[1]) / s);
            out->quaternions[1*N+i] = (float)((U[6] + U[2]) / s);
            out->quaternions[2*N+i] = (float)((U[5] + U[7]) / s);
            out->quaternions[3*N+i] = (float)(0.25 * s);
        }
    }
}